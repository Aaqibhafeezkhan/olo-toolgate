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
QLORA_MODEL='olo-qlora-hotfolder:e376fb40'
QLORA_REVISION='e376fb40c313a8827c4d5ce6ca2abe2b6fe7a4c6'


def select_acceleration(run, compose, source):
    """Probe the daemon's GPU access before adding the NVIDIA reservation."""
    configuration=json.loads(run(compose+['config','--format','json'],capture_output=True,text=True).stdout)
    image=configuration['services']['ollama']['image']
    probe=run(['docker','run','--rm','--network','none','--gpus','all',
        '--entrypoint','nvidia-smi',image,'-L'],check=False,capture_output=True,text=True)
    if probe.returncode==0 and 'GPU ' in probe.stdout:
        print('Docker NVIDIA GPU access verified; exposing all NVIDIA GPUs to Ollama.')
        return compose+['-f',str(source/'ollama.gpu.compose.yaml')]
    print('Docker NVIDIA GPU access unavailable; starting Ollama on CPU. Inference may be slower.')
    return compose


def installed_models(run, compose):
    result=run(compose+['exec','-T','ollama','ollama','list'],capture_output=True,text=True)
    return {line.split()[0] for line in result.stdout.splitlines()[1:] if line.split()}


def prepare_qlora(run, root, storage, compose):
    """Reuse a source-qualified local import, otherwise prepare inside Docker."""
    if QLORA_MODEL in installed_models(run, compose):
        print('Reusing local OLO QLoRA model: '+QLORA_MODEL)
        return
    run(['docker','build','-f','debug/profiled/qlora-builder.Dockerfile','-t','olo-qlora-builder:local','.'])
    output='/models/olo-import/'+QLORA_REVISION
    run(['docker','run','--rm','--memory','8g','--cpus','8','--mount',
        'type=volume,source='+storage+',target=/models','olo-qlora-builder:local','--output',output])
    model_path='/root/.ollama/olo-import/'+QLORA_REVISION+'/model-f16.gguf'
    # All arguments and paths here are fixed installation-owned constants.
    run(compose+['exec','-T','ollama','sh','-c',
        'printf "FROM '+model_path+'\\nPARAMETER num_ctx 4096\\n" > /root/.ollama/olo-import/Modelfile'])
    run(compose+['exec','-T','ollama','ollama','create',QLORA_MODEL,'-f','/root/.ollama/olo-import/Modelfile'])


def warm_models(run,compose,source,image,timeout=180):
    """Use the installed Python image in Ollama's network namespace, with stdin closed."""
    import re
    container=run(compose+['ps','-q','ollama'],capture_output=True,text=True).stdout.strip()
    if not re.fullmatch('[a-f0-9]{64}',container):raise ValueError('One running Ollama container is required')
    for model in (QLORA_MODEL,MODEL):
        print(f'Preloading local model {model} (timeout {timeout}s)...',flush=True)
        run(['docker','run','--rm','--network','container:'+container,'--read-only',
            '--cap-drop','ALL','--security-opt','no-new-privileges:true','--user','65532:65532',
            '-v',str(source/'ollama-warmup.py')+':/warmup.py:ro',
            '--entrypoint','/opt/quickstart-python/bin/python',image,
            '/warmup.py','--model',model,'--timeout',str(timeout)],
            stdin=subprocess.DEVNULL,timeout=timeout+15)


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
    parser.add_argument('operation',choices=['start','stop','activate','vault','initialize','configure'])
    args=parser.parse_args()
    if args.operation=='initialize':initialize();return
    source=Path(__file__).resolve().parent;here=Path(os.environ.get('OLO_STACK_HOME',str(source))).resolve();root=source.parents[1];sys.path.insert(0,str(root))
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
    sys.path.insert(0,str(source))
    import stack
    stack.check_owned()
    document=json.loads((here/'state/compose.json').read_text(encoding='utf-8'))
    env={**os.environ,'TOOLGATE_PROFILED_ROOT':str(here),'TOOLGATE_PROFILED_CUSTODY':document['volumes']['custody']['name'],
        'TOOLGATE_PROFILED_OLLAMA_STORAGE':document['volumes']['files']['name']+'-ollama-models'}
    compose=['docker','compose','-p',stack.PROJECT+'-model','-f',str(source/'ollama.compose.yaml')]
    def run(command,**kwargs):
        kwargs.setdefault('check',True)
        return subprocess.run(command,cwd=root,env=env,**kwargs)
    if args.operation=='stop':run(compose+['stop']);return
    if args.operation=='start':
        compose=select_acceleration(run,compose,source)
        run(['docker','run','--rm','--network','none','--user','0:0','--mount',
            'type=volume,source='+env['TOOLGATE_PROFILED_CUSTODY']+',target=/custody',
            '-v',str(source/'ollama.py')+':/ollama.py:ro','--entrypoint','/opt/quickstart-python/bin/python',
            document['services']['quickstart']['image'],'/ollama.py','initialize'])
        run(compose+['up','-d','--wait','--wait-timeout','180'])
        if MODEL not in installed_models(run, compose):
            run(compose+['exec','-T','ollama','ollama','pull',MODEL])
        else:print('Reusing local model: '+MODEL)
        prepare_qlora(run,root,env['TOOLGATE_PROFILED_OLLAMA_STORAGE'],compose)
        warm_models(run,compose,source,document['services']['quickstart']['image'])
        core=['docker','compose','-p',stack.PROJECT,'-f',str(here/'state/compose.json')]
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
        print('Ollama ready: '+QLORA_MODEL+' and '+MODEL+'; TLS origin https://ollama-provider:9443; no host model ports opened.')
        return
    from tools.admin.import_workflow import call,deploy
    if args.operation=='vault':
        key=run(['docker','compose','-p',stack.PROJECT,'-f',str(here/'state/compose.json'),
            'exec','-T','quickstart','cat','/custody/ollama/token'],capture_output=True,text=True).stdout.strip()
        call(stack.operator_client(),'/api/control/v1/vault',dict(name='ollama-local-key',value=key,
            toolGroupId='default-tools',deviceGroupId='default-devices'))
        print('Saved this installation\'s Ollama credential in the OLO vault; no bot or device files changed.')
        return
    bundle=root/'bot/bin/ollama-summary-ollama-summary-1.0.0-demo.ollama.olo'
    run([sys.executable,str(root/'bot/build.py'),'--configuration','ollama-summary','--version','1.0.0-demo.ollama'])
    key=run(['docker','compose','-p',stack.PROJECT,'-f',str(here/'state/compose.json'),
        'exec','-T','quickstart','cat','/custody/ollama/token'],capture_output=True,text=True).stdout.strip()
    client=stack.operator_client()
    def vault(_profile):call(client,'/api/control/v1/vault',dict(name='ollama-local-key',value=key,toolGroupId='default-tools',deviceGroupId='default-devices'))
    result=deploy(client,bundle.read_bytes(),after_provision=vault)
    print(json.dumps(result));print('Open Bots > ollama-summary > Run; enter a short incident to summarize.')


if __name__=='__main__':main()
