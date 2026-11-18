# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Optional local Ollama model companions; installation custody stays outside bot bundles."""
import argparse
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys

MODEL='qwen3:0.6b'


def initialize():
    # Executed with the existing Quickstart image's Python, never host pip.
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes,serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    root=Path('/custody/ollama');root.mkdir(mode=0o700,exist_ok=True)
    if not (root/'server.key').exists():
        now=datetime.now(timezone.utc);key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
        ca_key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
        issuer=x509.Name([x509.NameAttribute(x509.NameOID.COMMON_NAME,'Local Ollama demo CA')])
        def certificate(name,public):
            return (x509.CertificateBuilder().subject_name(name).issuer_name(issuer).public_key(public)
                .serial_number(x509.random_serial_number()).not_valid_before(now-timedelta(minutes=1)).not_valid_after(now+timedelta(days=30)))
        ca=certificate(issuer,ca_key.public_key()).add_extension(x509.BasicConstraints(ca=True,path_length=0),True).sign(ca_key,hashes.SHA256())
        cert=(certificate(x509.Name([x509.NameAttribute(x509.NameOID.COMMON_NAME,'ollama-provider')]),key.public_key())
            .add_extension(x509.SubjectAlternativeName([x509.DNSName('ollama-provider')]),False)
            .add_extension(x509.ExtendedKeyUsage([x509.oid.ExtendedKeyUsageOID.SERVER_AUTH]),False).sign(ca_key,hashes.SHA256()))
        token=secrets.token_urlsafe(32)
        values={'server.key':key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()),
            'server.crt':cert.public_bytes(serialization.Encoding.PEM),'ca.crt':ca.public_bytes(serialization.Encoding.PEM),
            'token':token.encode(),'authorization.conf':('set $expected_bearer "Bearer '+token+'";\nif ($http_authorization != $expected_bearer) { return 401; }\n').encode()}
        for name,raw in values.items():
            with open(root/name,'xb',opener=lambda p,f:os.open(p,f,0o600)) as stream:stream.write(raw)
            os.chown(root/name,65532,65532)
    os.chown(root,65532,65532)
    # Explicit operator-owned model trust bundle, separate from graph/client CAs.
    combined=(root/'ca.crt').read_bytes();mock=Path('/custody/provider/ca.crt')
    if mock.exists():combined+=b'\n'+mock.read_bytes()
    target=Path('/custody/model-ca.pem')
    with open(target,'wb',opener=lambda p,f:os.open(p,f,0o600)) as stream:stream.write(combined)
    os.chown(target,65532,65532)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation',choices=['start','stop','activate','initialize','configure'])
    args=parser.parse_args()
    if args.operation=='initialize':initialize();return
    here=Path(__file__).resolve().parent;root=here.parents[1];sys.path.insert(0,str(root))
    if args.operation=='configure':
        settings=here/'installation.env'
        lines=settings.read_text(encoding='utf-8').splitlines() if settings.exists() else []
        current=dict(line.split('=',1) for line in lines if line and not line.startswith('#') and '=' in line)
        ca=current.get('TOOLGATE_WORKFLOW_MODEL_CA_FILE','')
        if ca not in ('','/custody/provider/ca.crt','/custody/model-ca.pem'):
            raise ValueError('Custom model CA preserved. Combine its trust with the local Ollama CA manually before selecting local Ollama.')
        origins=[v.strip() for v in current.get('TOOLGATE_WORKFLOW_MODEL_ORIGINS','').split(',') if v.strip()]
        if 'https://ollama-provider:9443' not in origins:origins.append('https://ollama-provider:9443')
        updates={'TOOLGATE_WORKFLOW_MODEL_ORIGINS':','.join(origins),'TOOLGATE_WORKFLOW_MODEL_CA_FILE':'/custody/model-ca.pem'}
        lines=[line for line in lines if line.split('=',1)[0] not in updates]
        lines.extend(key+'='+value for key,value in updates.items())
        settings.write_text('\n'.join(lines)+'\n',encoding='utf-8')
        print('Local Ollama installation origin/trust configured; other settings retained. Bot activation remains explicit.')
        return
    # The standalone embeddable Python intentionally does not add script directories.
    sys.path.insert(0,str(here))
    import stack
    stack.check_owned()
    document=json.loads((here/'state/compose.json').read_text(encoding='utf-8'))
    env={**os.environ,'TOOLGATE_PROFILED_ROOT':str(here),'TOOLGATE_PROFILED_CUSTODY':document['volumes']['custody']['name'],
        'TOOLGATE_PROFILED_OLLAMA_STORAGE':document['volumes']['files']['name']+'-ollama-models'}
    compose=['docker','compose','-p','toolgate-profiled-model','-f',str(here/'ollama.compose.yaml')]
    def run(command,**kwargs):return subprocess.run(command,cwd=root,env=env,check=True,**kwargs)
    if args.operation=='stop':run(compose+['stop']);return
    if args.operation=='start':
        run(['docker','run','--rm','--network','none','--user','0:0','--mount',
            'type=volume,source='+env['TOOLGATE_PROFILED_CUSTODY']+',target=/custody',
            '-v',str(here/'ollama.py')+':/ollama.py:ro','--entrypoint','/opt/quickstart-python/bin/python',
            document['services']['quickstart']['image'],'/ollama.py','initialize'])
        run(compose+['up','-d','--wait','--wait-timeout','180'])
        run(compose+['exec','-T','ollama','ollama','pull',MODEL])
        run(compose+['exec','-T','ollama','ollama','run',MODEL,'--keepalive','24h',''])
        core=['docker','compose','-p','toolgate-profiled','-f',str(here/'state/compose.json')]
        ids=run(core+['ps','-q','quickstart'],capture_output=True,text=True).stdout.strip()
        actual=json.loads(run(['docker','inspect',ids],capture_output=True,text=True).stdout)[0]
        values=dict(item.split('=',1) for item in actual['Config']['Env'] if '=' in item)
        if ('https://ollama-provider:9443' not in values.get('TOOLGATE_WORKFLOW_MODEL_ORIGINS','').split(',')
                or values.get('TOOLGATE_WORKFLOW_MODEL_CA_FILE')!='/custody/model-ca.pem'):
            # Explicit provider installation can change process environment; bot imports never do this.
            run(core+['stop','langgraph'])
            run(core+['up','-d','--no-deps','--force-recreate','quickstart'])
            stack.sync_credentials(core);stack.provision_runtime(core)
            run(core+['up','-d','langgraph'])
        print('Ollama ready: '+MODEL+'; TLS origin https://ollama-provider:9443; no host model ports opened.')
        return
    from tools.workflows.hotswap_demo import login
    from tools.admin.import_workflow import call,deploy
    bundle=root/'bot/bin/ollama-summary-ollama-summary-1.0.0-demo.ollama.olo'
    run([sys.executable,str(root/'bot/build.py'),'--configuration','ollama-summary','--version','1.0.0-demo.ollama'])
    key=run(['docker','compose','-p','toolgate-profiled','-f',str(here/'state/compose.json'),
        'exec','-T','quickstart','cat','/custody/ollama/token'],capture_output=True,text=True).stdout.strip()
    client=login()
    def vault(_profile):call(client,'/api/control/v1/vault',dict(name='ollama-local-key',value=key,toolGroupId='default-tools',deviceGroupId='default-devices'))
    result=deploy(client,bundle.read_bytes(),after_provision=vault)
    print(json.dumps(result));print('Open Bots > ollama-summary > Run; enter a short incident to summarize.')


if __name__=='__main__':main()
