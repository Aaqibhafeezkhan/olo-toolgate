# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Signed fleet Helm configuration, narrow external-store egress and closed negatives."""
import sys
from pathlib import Path
import yaml
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'tools'))
from check import run

def checks():
    settings = {'control.enabled':'true','control.effect.signingSecret':'effect-custody','control.publicKeySecret':'idp','control.database.credentialsSecret':'db',
        'control.database.caSecret':'db-ca','control.endpoint.enabled':'true','control.endpoint.tenantId':'tenant',
        'control.endpoint.serverId':'server','control.endpoint.organization':'Organization',
        'control.endpoint.controlUrl':'https://control.example.test','control.endpoint.gatewayUrl':'https://gateway.example.test',
        'control.endpoint.deviceCaSecret':'device-ca','control.endpoint.tlsSecret':'tls','control.endpoint.trustStoreSecret':'device-trust',
        'control.fleet.enabled':'true','control.fleet.keyId':'organization','control.fleet.signingSecret':'fleet-signing',
        'control.fleet.trustSecret':'fleet-trust','control.fleet.artifactOrigin':'https://artifacts.example.test',
        'control.fleet.artifactSecret':'mirror','control.networkPolicy.artifactTo[0].ipBlock.cidr':'192.0.2.0/24'}
    def render(values, negative=False):
        return run(['helm','template','fleet','deploy/helm/olo-toolgate',
                    *[arg for key,value in values.items() for arg in ['--set',key+'='+value]]],
                   capture=not negative, expect_failure=negative)
    documents = list(yaml.safe_load_all(render(settings).stdout))
    config = next(d for d in documents if d['kind']=='ConfigMap')['data']
    assert config['TOOLGATE_CONTROL_FLEET_ENABLED']=='true'
    assert config['TOOLGATE_CONTROL_FLEET_ARTIFACT_ORIGIN']=='https://artifacts.example.test'
    pod = next(d for d in documents if d['kind']=='Deployment')['spec']['template']['spec']
    assert {'fleet-signing','fleet-trust','artifact-store'} <= {v['name'] for v in pod['volumes']}
    assert not any(d['kind']=='Secret' for d in documents)
    network = next(d for d in documents if d['kind']=='NetworkPolicy')
    assert any(r.get('to')==[{'ipBlock':{'cidr':'192.0.2.0/24'}}] and r['ports'][0]['port']==443 for r in network['spec']['egress'])
    for key,value in [('control.fleet.signingSecret',''),('control.fleet.trustSecret',''),
                      ('control.endpoint.enabled','false'),('control.fleet.artifactOrigin','http://unsafe.example.test')]:
        invalid = dict(settings); invalid[key]=value; render(invalid,True)
    invalid = dict(settings); del invalid['control.networkPolicy.artifactTo[0].ipBlock.cidr']; render(invalid,True)
    render(dict(settings, **{'control.builder.enabled':'true'}), True)
    builder = list(yaml.safe_load_all(render(dict(settings, **{'control.builder.enabled':'true','control.builder.existingClaim':'sdk-artifacts'})).stdout))
    builder_config = next(d for d in builder if d['kind']=='ConfigMap')['data']
    assert builder_config['TOOLGATE_CONTROL_BUILDER_ENABLED']=='true'
    builder_pod = next(d for d in builder if d['kind']=='Deployment')['spec']['template']['spec']
    assert next(v for v in builder_pod['volumes'] if v['name']=='builder-data')['persistentVolumeClaim']['claimName']=='sdk-artifacts'
    assert next(v for v in builder_pod['containers'][0]['volumeMounts'] if v['name']=='builder-data')['mountPath']=='/data/builder'
    print('Fleet external secret/store render and five negative configurations passed')

if __name__=='__main__': checks()
