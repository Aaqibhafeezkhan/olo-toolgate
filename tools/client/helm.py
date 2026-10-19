# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Render direct-TLS enrollment configuration; reject missing keys and insecure origins."""
import sys
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools'))
from check import run
import yaml

def checks():
    base = ['helm','template','endpoint','deploy/helm/olo-toolgate']
    settings = {'control.enabled':'true','control.effect.signingSecret':'effect-custody','control.publicKeySecret':'idp','control.database.credentialsSecret':'db',
        'control.database.caSecret':'db-ca','control.endpoint.enabled':'true','control.defaultTenantAlias':'tenant',
        'control.endpoint.serverId':'server','control.endpoint.organization':'Organization',
        'control.endpoint.controlUrl':'https://control.example.test','control.endpoint.gatewayUrl':'https://gateway.example.test',
        'control.endpoint.deviceCaSecret':'device-ca','control.endpoint.tlsSecret':'server-tls','control.endpoint.trustStoreSecret':'device-trust'}
    def arguments(values): return base + [arg for name,value in values.items() for arg in ['--set',name+'='+value]]
    result = run(arguments(settings),capture=True)
    documents = list(yaml.safe_load_all(result.stdout))
    config = next(doc for doc in documents if doc['kind']=='ConfigMap')['data']
    assert config['QUARKUS_HTTP_INSECURE_REQUESTS']=='disabled'
    assert config['QUARKUS_HTTP_SSL_CLIENT_AUTH']=='request'
    assert config['QUARKUS_HTTP_SSL_PORT']=='8082'
    assert config['TOOLGATE_CLIENT_DOWNLOADS_DIRECTORY']==''
    enabled=dict(settings);enabled['control.clientDownloads.enabled']='true'
    enabled_docs=list(yaml.safe_load_all(run(arguments(enabled),capture=True).stdout))
    assert next(doc for doc in enabled_docs if doc['kind']=='ConfigMap')['data']['TOOLGATE_CLIENT_DOWNLOADS_DIRECTORY']=='/opt/toolgate/client-downloads'
    deployment = next(doc for doc in documents if doc['kind']=='Deployment')
    volumes = deployment['spec']['template']['spec']['volumes']
    assert {v['secret']['secretName'] for v in volumes if v['name'] in ('device-ca','server-tls','device-trust')} == {'device-ca','server-tls','device-trust'}
    output = ROOT/'build/client/helm'; output.mkdir(parents=True,exist_ok=True)
    (output/'endpoint.yaml').write_text(result.stdout)
    run(['docker','run','--rm','-v',f'{ROOT.as_posix()}:/work:ro','ghcr.io/yannh/kubeconform:v0.6.7',
         '-strict','-summary','-kubernetes-version','1.32.0','/work/build/client/helm/endpoint.yaml'])
    for name,value in [('control.endpoint.deviceCaSecret',''),('control.endpoint.tlsSecret',''),
                       ('control.endpoint.trustStoreSecret',''),('control.endpoint.controlUrl','http://control.example.test')]:
        invalid = dict(settings); invalid[name]=value
        run(arguments(invalid),expect_failure=True)
    print('Endpoint direct-TLS Helm render and four negatives passed')

if __name__ == '__main__': checks()
