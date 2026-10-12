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
    variants = {'base':[], 'ha':['--set','control.autoscaling.enabled=true'],
                'ingress':['--set','control.ingress.enabled=true','--set','control.ingress.className=nginx','--set','control.ingress.tlsSecret=control-tls'],
                'monitor':['--set','control.serviceMonitor.enabled=true'],
                'tracing':['--set','control.tracing.enabled=true','--set','control.networkPolicy.collectorTo[0].podSelector.matchLabels.app=collector'],
                'selectors':['--set','control.networkPolicy.databaseTo[0].podSelector.matchLabels.app=postgres',
                             '--set',r'control.networkPolicy.dnsTo[0].namespaceSelector.matchLabels.kubernetes\.io/metadata\.name=kube-system']}
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
            policy=next(doc for doc in docs if doc['kind']=='NetworkPolicy')
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
                  ['--set','control.clientCheckIn.checkInMs=22000'],['--set','control.clientCheckIn.jitterPct=51']):
        run(['helm','template','invalid',chart,*base,*flags],expect_failure=True)
    print('Control Helm install/upgrade variants, strict schemas and negative configuration passed')


if __name__=='__main__': checks()
