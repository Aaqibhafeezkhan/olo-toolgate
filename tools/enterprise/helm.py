# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Online authority render: dedicated Control custody, reviewed recovery, no legacy Gateway policy."""
import json
import sys
from pathlib import Path
import yaml
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools'))
from check import run

def checks():
    values={'gateway.enabled':'true','gateway.credentialsSecret':'workload-facts',
        'gateway.control.tokenSecret':'gateway-service','gateway.control.tenantId':'10000000-0000-4000-8000-000000000001','gateway.config.tenantHosts[0]':'gateway.example.com','gateway.networkPolicy.controlTo[0].podSelector.matchLabels.role':'control',
        r'gateway.networkPolicy.dnsTo[0].namespaceSelector.matchLabels.kubernetes\.io/metadata\.name':'kube-system',
        'control.enabled':'true','control.publicKeySecret':'identity','control.database.credentialsSecret':'db',
        'control.database.caSecret':'db-ca','control.effect.signingSecret':'effect-custody',
        'control.bundle.enabled':'true','control.bundle.signingSecret':'graph-custody',
        'control.recovery.enabled':'true','control.recovery.packetSecret':'reviewed-recovery',
        'control.recovery.trustSecret':'review-authority'}
    def render(extra=(),negative=False):return run(['helm','template','enterprise','deploy/helm/olo-toolgate',*[arg for key,value in values.items() for arg in ('--set',key+'='+value)],*extra],capture=not negative,expect_failure=negative)
    folder=ROOT/'build/enterprise/render';folder.mkdir(parents=True,exist_ok=True)
    for label,extra in [('install',[]),('upgrade',['--is-upgrade'])]:
        rendered=render(extra).stdout;docs=[d for d in yaml.safe_load_all(rendered) if d]
        gateway=json.loads(next(d for d in docs if d['kind']=='ConfigMap' and 'gateway.json' in d.get('data',{}))['data']['gateway.json'])
        assert set(gateway)=={'listen','managementListen','trustedTlsProxy','allowedOrigins','limits','control','tenantHosts'}
        for d in [d for d in docs if d['kind']=='Deployment']:
            volumes={v['name']:v for v in d['spec']['template']['spec']['volumes']}
            if d['metadata']['name'].endswith('gateway'):assert 'effect-signing' not in volumes and 'bundle-signing' not in volumes
            else:
                assert volumes['effect-signing']['secret']['secretName']=='effect-custody'
                assert volumes['effect-signing']['secret']['defaultMode']==0o440
                assert {'recovery-packet','recovery-trust'}<=set(volumes)
        assert not any(d['kind']=='Secret' for d in docs)
        (folder/(label+'.yaml')).write_text(rendered,encoding='utf-8')
    for override in ['gateway.control.tokenSecret=','gateway.config.policy.defaultDecision=ALLOW','gateway.approval.enabled=true','gateway.bundle.enabled=true','control.effect.signingSecret=','control.recovery.trustSecret=','control.recovery.packetSecret=','gateway.control.url=http://untrusted.example','gateway.config.limits.requestTimeoutMs=9000']:
        render(['--set',override],True)
    print('Enterprise custody, online authority and nine closed negative Helm configurations passed')

if __name__=='__main__':checks()
