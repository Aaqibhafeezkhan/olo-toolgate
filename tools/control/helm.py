# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Control Plane Helm install/upgrade rendering and strict Kubernetes schema validation."""
import json
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools'))
from check import run
from gateway.check import monitor_validator


def checks(lint=True):
    chart = 'deploy/helm/olo-toolgate'
    if lint: run(['helm','lint','--strict',chart])
    base = ['--set','control.enabled=true','--set','control.publicKeySecret=control-identity',
            '--set','control.database.credentialsSecret=control-db','--set','control.database.caSecret=control-db-ca','--set','control.effect.signingSecret=effect-custody']
    cache = ['--set','control.cache.mode=redis','--set','control.cache.host=cache.example.com',
             '--set','control.cache.namespace=production','--set','control.cache.credentialsSecret=cache-custody',
             '--set','control.cache.port=6380','--set','control.networkPolicy.cacheTo[0].ipBlock.cidr=192.0.2.10/32',
             '--set','control.networkPolicy.dnsTo[0].podSelector.matchLabels.app=dns']
    variants = {'base':[], 'ha':['--set','control.autoscaling.enabled=true'],
                'ingress':['--set','control.ingress.enabled=true','--set','control.ingress.className=nginx','--set','control.ingress.tlsSecret=control-tls'],
                'monitor':['--set','control.serviceMonitor.enabled=true'],
                'tracing':['--set','control.tracing.enabled=true','--set','control.networkPolicy.collectorTo[0].podSelector.matchLabels.app=collector'],
                'marketplace':['--set','control.marketplace.enabled=true','--set','control.marketplace.id=olo-orgs','--set','control.marketplace.baseUrl=https://marketplace.example.com',
                               '--set','control.marketplace.keys=olo-orgs-2026:'+'A'*43+'=','--set','control.marketplace.caSecret=marketplace-ca',
                               '--set','control.networkPolicy.marketplaceTo[0].ipBlock.cidr=203.0.113.0/24'],
                'packages':['--set','control.profile.configMap=control-profile','--set','control.clientImages.enabled=true',
                            '--set','control.clientImages.registryUrl=https://registry.example.com:5443','--set','control.clientImages.repository=toolgate/client-tools',
                            '--set','control.clientImages.base=mirror/eclipse-temurin@sha256:'+'0'*64,'--set','control.clientImages.caSecret=registry-ca',
                            '--set','control.clientImages.credentialsSecret=registry-credentials','--set','control.packageRelease.signingSecret=package-release',
                            '--set','control.packageRelease.keyId=package-1','--set','control.networkPolicy.registryTo[0].ipBlock.cidr=198.51.100.0/24'],
                'selectors':['--set','control.networkPolicy.databaseTo[0].podSelector.matchLabels.app=postgres',
                             '--set',r'control.networkPolicy.dnsTo[0].namespaceSelector.matchLabels.kubernetes\.io/metadata\.name=kube-system']}
    variants['cache'] = cache
    variants['cache-private-ca'] = [*cache,'--set','control.cache.caSecret=cache-ca']
    folder = ROOT/'build/control/render'; folder.mkdir(parents=True,exist_ok=True)
    for name,flags in variants.items():
        for upgrade in ([],['--is-upgrade']):
            rendered = run(['helm','template','control',chart,*base,*flags,*upgrade],capture=True).stdout
            docs = [doc for doc in yaml.safe_load_all(rendered) if doc]
            kinds = {doc['kind'] for doc in docs}
            assert {'Deployment','Service','ServiceAccount','ConfigMap','NetworkPolicy','PodDisruptionBudget'} <= kinds
            deployment = next(doc for doc in docs if doc['kind']=='Deployment')
            pod = deployment['spec']['template']['spec']; container = pod['containers'][0]
            assert not pod['automountServiceAccountToken'] and pod['securityContext']['runAsNonRoot']
            assert container['securityContext']['readOnlyRootFilesystem'] and not container['securityContext']['allowPrivilegeEscalation']
            assert {port['containerPort'] for port in container['ports']} == {8082,9092}
            assert all(container[probe]['httpGet']['port']=='management' for probe in ('startupProbe','readinessProbe','livenessProbe'))
            assert len([v for v in container['env'] if 'valueFrom' in v]) == 4
            assert any(v.get('emptyDir',{}).get('sizeLimit')=='64Mi' for v in pod['volumes'])
            config = next(doc for doc in docs if doc['kind']=='ConfigMap')['data']
            assert 'sslmode=verify-full' in config['QUARKUS_DATASOURCE_JDBC_URL']
            assert config['TOOLGATE_CONTROL_DEVELOPMENT_MODE']=='false'
            assert config['TOOLGATE_CONTROL_MAX_RECORDS']=='512'
            assert config['TOOLGATE_CONTROL_MAX_CONFIG_BYTES']=='1048576'
            assert config['TOOLGATE_CONTROL_TRACE_EXPORT_ENABLED']==('true' if name=='tracing' else 'false')
            assert (config['TOOLGATE_CONTROL_CLIENT_CHECKIN_MS'],config['TOOLGATE_CONTROL_CLIENT_CHECKIN_JITTER_PCT'])==('2000','20')
            assert 'TOOLGATE_CONTROL_CLIENT_OFFLINE_AFTER_MS' not in config
            assert config['TOOLGATE_CONTROL_MARKETPLACE_ENABLED']==('true' if name=='marketplace' else 'false')
            cached = name.startswith('cache')
            assert config['TOOLGATE_CONTROL_CACHE_MODE']==('redis' if cached else 'local')
            cache_volumes = [v for v in pod['volumes'] if v['name']=='cache-credentials']
            assert bool(cache_volumes)==cached
            if cached:
                assert config['TOOLGATE_CONTROL_CACHE_HOST']=='cache.example.com'
                assert config['TOOLGATE_CONTROL_CACHE_PORT']=='6380'
                assert config['TOOLGATE_CONTROL_CACHE_PASSWORD_PATH']=='/etc/toolgate/cache/password'
                assert config['TOOLGATE_CONTROL_CACHE_ENCRYPTION_KEY_PATH']=='/etc/toolgate/cache/encryption-key'
                assert cache_volumes[0]['secret']['secretName']=='cache-custody'
                assert {v['key'] for v in cache_volumes[0]['secret']['items']}=={'password','encryption-key'}
                assert any(m['name']=='cache-credentials' and m['readOnly'] for m in container['volumeMounts'])
            else:
                assert not any(key.startswith('TOOLGATE_CONTROL_CACHE_') and key!='TOOLGATE_CONTROL_CACHE_MODE' for key in config)
            assert ('TOOLGATE_CONTROL_CACHE_CA_PATH' in config)==(name=='cache-private-ca')
            if name=='marketplace':
                assert (config['TOOLGATE_CONTROL_MARKETPLACE_ID'],config['TOOLGATE_CONTROL_MARKETPLACE_BASE_URL'])==('olo-orgs','https://marketplace.example.com')
                assert config['TOOLGATE_CONTROL_MARKETPLACE_CA_PATH']=='/etc/toolgate/marketplace-ca/ca.pem'
                assert any(m['mountPath']=='/etc/toolgate/marketplace-ca' for m in container['volumeMounts'])
            else: assert not any(key.startswith('TOOLGATE_CONTROL_MARKETPLACE_') and key!='TOOLGATE_CONTROL_MARKETPLACE_ENABLED' for key in config)
            policy=next(doc for doc in docs if doc['kind']=='NetworkPolicy')
            cache_rules=[rule for rule in policy['spec'].get('egress',[]) if any(port['port']==6380 for port in rule['ports'])]
            assert bool(cache_rules)==cached
            if cached: assert cache_rules[0]['to']==[{'ipBlock':{'cidr':'192.0.2.10/32'}}]
            assert any(rule['ports']==[{'protocol':'TCP','port':443}] for rule in policy['spec'].get('egress',[]))==(name=='marketplace')
            collector_rules=[rule for rule in policy['spec']['egress'] if any(port['port']==4317 for port in rule['ports'])]
            assert bool(collector_rules)==(name=='tracing')
            assert not any(doc['kind']=='Secret' for doc in docs)
            monitor = next((doc for doc in docs if doc['kind']=='ServiceMonitor'),None)
            if monitor:
                assert monitor['spec']['endpoints'][0]['path']=='/q/metrics'; monitor_validator().validate(monitor)
            suffix = '-upgrade' if upgrade else ''; path = folder/f'{name}{suffix}.yaml';path.write_text(rendered,encoding='utf-8',newline='\n')
            run(['docker','run','--rm','-v',f'{ROOT.as_posix()}:/work:ro','ghcr.io/yannh/kubeconform:v0.6.7','-strict','-summary','-kubernetes-version','1.32.0','-skip','ServiceMonitor',f'/work/{path.relative_to(ROOT).as_posix()}'])
    for flags in (['--set','control.effect.signingSecret='],['--set','control.publicKeySecret='],['--set','control.database.credentialsSecret='],['--set','control.database.caSecret='],
                  ['--set','control.database.sslMode=disable'],['--set','control.securityContext.allowPrivilegeEscalation=true'],
                  ['--set','control.terminationGracePeriodSeconds=20'],['--set','control.limits.maxRecords=513'],
                  ['--set','control.autoscaling.enabled=true','--set','control.autoscaling.minReplicas=9'],['--set','control.ingress.enabled=true'],
                  ['--set','control.tracing.enabled=true'],['--set','control.clientCheckIn.checkInMs=400'],
                  ['--set','control.clientCheckIn.checkInMs=22000'],['--set','control.clientCheckIn.jitterPct=51'],
                  ['--set','control.marketplace.enabled=true'],
                  ['--set','control.marketplace.enabled=true','--set','control.marketplace.id=olo-orgs','--set','control.marketplace.keys=k:x','--set','control.marketplace.baseUrl=http://marketplace.example.com']):
        run(['helm','template','invalid',chart,*base,*flags],expect_failure=True)
    for key,value in [('host',''),('namespace',''),('namespace','invalid space'),('credentialsSecret',''),
                      ('tls','false'),('port','0'),('timeoutMs','49'),('ttlSeconds','3601'),('password','unsafe')]:
        run(['helm','template','invalid',chart,*base,*cache,'--set',f'control.cache.{key}={value}'],expect_failure=True)
    for peers in ('cacheTo','dnsTo'):
        without_peer = [arg for flag,setting in zip(cache[::2],cache[1::2])
                        if not setting.startswith(f'control.networkPolicy.{peers}[') for arg in (flag,setting)]
        run(['helm','template','invalid',chart,*base,*without_peer,'--set-json',f'control.networkPolicy.{peers}=[]'],expect_failure=True)
    print('Control Helm install/upgrade variants, strict schemas and negative configuration passed')


if __name__=='__main__': checks()
