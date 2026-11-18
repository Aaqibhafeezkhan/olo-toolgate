# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Start the blank local installation; workflow changes use Control APIs only."""
import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.request
import uuid

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[1]
STATE=HERE/'state'
PROJECT='toolgate-profiled'
REGISTRY='registry:3.0.0@sha256:6c5666b861f3505b116bb9aa9b25175e71210414bd010d92035ff64018f9457e'
BASE='eclipse-temurin@sha256:000fd431958bc81a24abe1e8e5f0f0fd3ae365a594bd50aadb20696805f9408c'


def run(args,**kwargs):
    return subprocess.run(args,cwd=ROOT,check=True,**kwargs)


def image_exists(image):
    return subprocess.run(['docker','image','inspect',image],capture_output=True).returncode==0


def storage_prefix():
    path=STATE/'storage.json'
    if not path.exists():return PROJECT
    value=json.loads(path.read_text(encoding='utf-8'))
    import re
    if set(value)!={'formatVersion','prefix'} or value['formatVersion']!=1 or not re.fullmatch(PROJECT+r'(?:-[a-z0-9-]{1,64})?',value['prefix']):
        raise ValueError('Unsupported installation storage selection; existing volumes preserved')
    return value['prefix']


def image_tag(module):
    prefixes={
        'quickstart':('apps/quickstart/','apps/control-plane/','apps/admin-ui/','apps/gateway/','apps/tool-host/',
            'apps/endpoint-client/','backend-libs/','packages/','crates/','tool-sdk/','core-mcp/','config/initial/',
            'gradle/','tools/quickstart/','tools/gateway/','tools/enterprise/','tools/access/',
            'deploy/compose/WorkflowStack/scripts/','examples/java-tools/'),
        'device':('apps/endpoint-client/','apps/gateway/','apps/tool-host/','packages/','crates/','tool-sdk/'),
        'runtime':('apps/langgraph-runtime/',)}[module]
    fixed={'Cargo.toml','Cargo.lock','rust-toolchain.toml','VERSION','TOOL-SDK-GUIDE.md','LICENSE','NOTICE.md',
           'RELEASE-NOTES.md','gradlew','gradlew.bat','settings.gradle.kts','build.gradle.kts','gradle.properties',
           'package.json','package-lock.json','.dockerignore'}
    if module=='device':fixed|={'debug/profiled/linux-device.Dockerfile','debug/profiled/device.py',
        'debug/profiled/device-entrypoint.py','debug/profiled/device-systemctl.sh'}
    if module=='quickstart':fixed|={'deploy/profiles/fresh.json','debug/profiled/configure-debug-agent.py',
        'debug/profiled/agent-mimic.py','examples/langchain/manage.py','examples/langchain/packages.py',
        'docs/getting-started/one-minute-quickstart.md','docs/deployment/quickstart.md'}
    # Use the same source inventory with or without Git, including source ZIPs.
    names=list(fixed)
    excluded={'.git','.dev','.gradle','node_modules','build','dist','target','__pycache__','.venv','bin','coverage','test-results'}
    for prefix in prefixes:
        for directory,folders,files in os.walk(ROOT/prefix,followlinks=False):
            relative=Path(directory).relative_to(ROOT).as_posix()
            folders[:]=[name for name in folders if name not in excluded and not (Path(directory)/name).is_symlink()
                and not ('/src/' not in '/'+relative+'/' and name in ('credentials','secrets','private','.data','.state','.local-secrets'))]
            names.extend((Path(directory)/name).relative_to(ROOT).as_posix() for name in files
                if not name.startswith('.env') and not name.endswith(('.pyc','.secret','.secrets','.pem','.key')))
    digest=hashlib.sha256()
    for name in sorted(set(names)-{''}):
        if name not in fixed and not name.startswith(prefixes):continue
        path=ROOT/name
        if not path.is_file():continue
        if path.is_symlink() or not path.resolve().is_relative_to(ROOT):raise ValueError('Linked image source refused')
        digest.update(name.encode()+b'\0'+path.read_bytes()+b'\0')
    return 'olo-toolgate-'+module+':profiled-'+digest.hexdigest()[:20]


def images():
    selected={module:image_tag(module) for module in ('quickstart','device','runtime')}
    for module,image in selected.items():
        if image_exists(image):
            print('Reusing '+module+' image '+image,flush=True);continue
        print('Building missing '+module+' image '+image,flush=True)
        if module=='quickstart':
            # Keep all Node, Java, Rust and Python build dependencies in Docker.
            version=(ROOT/'VERSION').read_text().strip()
            metadata=['--build-arg','VERSION='+version,'--build-arg','REVISION='+image.rsplit(':',1)[1]]
            control='olo-toolgate-control:'+image.rsplit(':',1)[1]
            if not image_exists(control):
                run(['docker','build','-f','apps/control-plane/Dockerfile',*metadata,
                     '--build-arg','QUARKUS_PROFILE=quickstart','-t',control,'.'])
            run(['docker','build','-f','apps/quickstart/Dockerfile',*metadata,
                 '--build-arg','CONTROL_IMAGE='+control,'-t',image,'.'])
        else:
            dockerfile='debug/profiled/linux-device.Dockerfile' if module=='device' else 'apps/langgraph-runtime/Dockerfile'
            context='.' if module=='device' else 'apps/langgraph-runtime'
            run(['docker','build','-f',dockerfile,'-t',image,context])
    return selected


def private(path,data):
    path.parent.mkdir(parents=True,exist_ok=True,mode=0o700)
    if os.name=='nt':
        account=run(['whoami','/user','/fo','csv','/nh'],capture_output=True,text=True).stdout
        sid=next(csv.reader(account.strip().splitlines()))[-1]
        if not sid.startswith('S-1-') or any(c not in 'S-0123456789' for c in sid):raise ValueError('Cannot resolve private-folder owner')
        run(['icacls',str(path.parent),'/inheritance:r','/grant:r','*'+sid+':(OI)(CI)F'],capture_output=True,text=True)
    else:path.parent.chmod(0o700)
    with open(path,'x',encoding='utf-8',opener=lambda p,f:os.open(p,f,0o600)) as stream:stream.write(data)


def compose(document):
    path=STATE/'compose.json'
    path.write_text(json.dumps(document,indent=2)+'\n',encoding='utf-8')
    return ['docker','compose','-p',PROJECT,'-f',str(path)]


def sync_credentials(command,timeout=180):
    folder=HERE/'credentials/admin'
    password_file=folder/'password.txt'
    candidate=password_file.read_text(encoding='utf-8-sig').rstrip('\r\n') if password_file.exists() else None
    script=(HERE/'credential-state.py').read_text(encoding='utf-8')
    deadline=time.monotonic()+timeout
    while True:
        result=subprocess.run(command+['exec','-T','quickstart','/opt/quickstart-python/bin/python','-c',script],
            input=json.dumps(dict(password=candidate)),capture_output=True,text=True)
        if result.returncode==0:break
        if time.monotonic()>=deadline:raise ValueError('Administrator state unavailable; credentials preserved')
        time.sleep(2)
    value=json.loads(result.stdout)
    if value['state']!='READY':
        raise ValueError('Saved password does not match the running Admin login. Put the current password in '+str(password_file)+'. No password was reset.')
    ca_file=folder/'gateway-ca.crt'
    if ca_file.exists() and ca_file.read_text(encoding='ascii').strip()!=value['ca'].strip() and not value['initialAvailable']:
        raise ValueError('Installation trust changed without valid initial custody; local files preserved')
    history=folder/'history'/uuid.uuid4().hex
    for name,data in {'username.txt':'admin\n','password.txt':value['password']+'\n','gateway-ca.crt':value['ca']}.items():
        path=folder/name
        if path.exists() and path.read_text(encoding='utf-8')==data:continue
        if path.exists():private(history/name,path.read_text(encoding='utf-8'))
        temporary=folder/(name+'.'+uuid.uuid4().hex+'.next')
        private(temporary,data);os.replace(temporary,path)
    print('Administrator credentials verified: '+str(password_file)+' (username: admin; password not logged).',flush=True)


def definition(selected,base=None):
    owner={'io.ololabs.toolgate.profiled-root':str(HERE),'io.ololabs.toolgate.profiled-format':'1'}
    script=str(HERE/'services.py')+':/installation/services.py:ro'
    control='https://localhost:18450'
    secure=dict(read_only=True,cap_drop=['ALL'],security_opt=['no-new-privileges:true'])
    services={
        'registry':dict(image=REGISTRY,environment={'REGISTRY_HTTP_ADDR':'0.0.0.0:18552'},
            ports=['127.0.0.1:18090:8080','127.0.0.1:18450:8443','127.0.0.1:18552:18552'],
            volumes=['registry:/var/lib/registry'],restart='unless-stopped'),
        'initialize':dict(image=selected['quickstart'],user='0:0',entrypoint=['/opt/quickstart-python/bin/python','/installation/services.py','initialize'],
            network_mode='none',volumes=[script,'custody:/custody'],cap_add=['CHOWN','FOWNER','DAC_OVERRIDE'],**secure),
        'quickstart':dict(image=selected['quickstart'],network_mode='service:registry',
            depends_on={'registry':{'condition':'service_started'},'initialize':{'condition':'service_completed_successfully'}},
            environment={'TOOLGATE_QUICKSTART_ADMIN_ONLY':'true','TOOLGATE_DISABLE_ADMIN_PASSWORD':'false',
                'TOOLGATE_CONTROL_PROFILE_PATH':'','TOOLGATE_CONTROL_CORE_PACKAGES_LOCK_PATH':'',
                'TOOLGATE_TRIGGERS_CONTROL_CORE_ENABLED':'true',
                'TOOLGATE_QUICKSTART_DATABASE_MODE':'sqlite','TOOLGATE_QUICKSTART_CLIENT_CREDENTIALS':'true',
                'TOOLGATE_CONTROL_ENDPOINT_CONTROL_URL':control,'TOOLGATE_CONTROL_ENDPOINT_GATEWAY_URL':control,
                'TOOLGATE_CONTROL_ENDPOINT_CONSOLE_URL':'http://127.0.0.1:18090',
                'TOOLGATE_CONTROL_RUNTIME_BUILDER_ORIGIN':'https://localhost:19460',
                'TOOLGATE_CONTROL_RUNTIME_BUILDER_IDENTITY_PATH':'/custody/control/identity.p12',
                'TOOLGATE_CONTROL_RUNTIME_BUILDER_PASSWORD_PATH':'/custody/control/password',
                'TOOLGATE_CONTROL_RUNTIME_BUILDER_CA_PATH':'/custody/control/ca.crt',
                'TOOLGATE_CONTROL_CLIENT_IMAGES_ENABLED':'true',
                'TOOLGATE_CONTROL_CLIENT_IMAGES_REGISTRY_URL':'http://localhost:18552',
                'TOOLGATE_CONTROL_CLIENT_IMAGES_REPOSITORY':'toolgate/client-tools',
                'TOOLGATE_CONTROL_CLIENT_IMAGES_BASE':base or 'toolgate/java-base@sha256:'+'0'*64,
                'TOOLGATE_CONTROL_CLIENT_IMAGES_PLATFORMS':'LINUX'},
            volumes=['data:/data','custody:/custody:ro'],tmpfs=['/tmp:rw,noexec,nosuid,size=128m,uid=65532,gid=65532'],
            restart='unless-stopped',stop_grace_period='35s',**secure),
        'device':dict(image=selected['device'],hostname='toolgate-profiled-device',
            depends_on={'quickstart':{'condition':'service_healthy'}},
            environment={'TOOLGATE_DEVICE_UPSTREAM':'registry','TOOLGATE_DEVICE_HTTP_PORT':'18090',
                         'TOOLGATE_DEVICE_TLS_PORT':'18450','TOOLGATE_DEVICE_WAIT_TIMEOUT':'0'},
            volumes=['device-config:/etc/olo-toolgate','device-state:/var/lib/olo-toolgate','files:/var/lib/olo-toolgate/files',
                     dict(type='volume',source='custody',target='/installation-trust',read_only=True,volume=dict(subpath='device')),
                     '/var/run/docker.sock:/var/run/docker.sock'],
            tmpfs=['/run:rw,nosuid,size=8m,mode=755'],cap_drop=['ALL'],security_opt=['no-new-privileges:true'],restart='unless-stopped'),
        'identity':dict(image=selected['quickstart'],user='0:0',network_mode='service:registry',
            depends_on={'quickstart':{'condition':'service_healthy'}},
            entrypoint=['/opt/quickstart-python/bin/python','-u','/installation/services.py','identities'],
            environment={'CONTROL_ORIGIN':control},volumes=[script,'data:/data:ro','custody:/custody'],
            cap_add=['CHOWN','FOWNER','DAC_OVERRIDE'],restart='unless-stopped',**secure)}
    for module in ('worker','graph','listener'):
        services[module]=dict(image=selected['runtime'],network_mode='service:registry',
            depends_on={'identity':{'condition':'service_started'}},
            entrypoint=['/opt/toolgate-runtime/bin/python','-u','/installation/services.py',module],
            environment={'CONTROL_ORIGIN':control},volumes=[script,'custody:/custody:ro'],restart='unless-stopped',**secure)
    settings=HERE/'installation.env'
    if settings.exists():services['quickstart']['env_file']=[str(settings)]
    for service in services.values():service['labels']=owner
    return dict(services=services,volumes={name:dict(labels=owner,name=storage_prefix()+'_'+name) for name in
                ('registry','data','custody','device-config','device-state','files')},networks={'default':dict(labels=owner)})


def registry_base(command):
    run(command+['up','-d','--no-recreate','registry'])
    if not image_exists(BASE):run(['docker','pull','--platform','linux/amd64',BASE])
    target='localhost:18552/toolgate/java-base:approved'
    run(['docker','tag',BASE,target]);run(['docker','push',target])
    request=urllib.request.Request('http://localhost:18552/v2/toolgate/java-base/manifests/approved',
        headers={'Accept':'application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json'})
    with urllib.request.urlopen(request,timeout=20) as response:
        return 'toolgate/java-base@sha256:'+hashlib.sha256(response.read()).hexdigest()


def check_owned():
    raw=run(['docker','volume','ls','--filter','name='+storage_prefix()+'_','--format','{{.Name}}'],capture_output=True,text=True).stdout
    for name in raw.splitlines():
        item=json.loads(run(['docker','volume','inspect',name],capture_output=True,text=True).stdout)[0]
        labels=item.get('Labels') or {}
        if labels.get('io.ololabs.toolgate.profiled-root')!=str(HERE) or labels.get('io.ololabs.toolgate.profiled-format')!='1':
            raise ValueError('Existing volume is unsupported or belongs elsewhere; preserved: '+name)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation',choices=['start','stop','restart','validate'])
    args=parser.parse_args()
    try:engine=subprocess.run(['docker','info','--format','{{.OSType}}'],capture_output=True,text=True)
    except FileNotFoundError:raise ValueError('Install Docker Desktop and start its Linux container engine, then run start.bat again.') from None
    if engine.returncode!=0 or engine.stdout.strip()!='linux':
        raise ValueError('Start Docker Desktop with its Linux container engine, then run start.bat again.')
    STATE.mkdir(parents=True,exist_ok=True)
    if args.operation in ('stop','restart'):
        if not (STATE/'compose.json').exists():raise ValueError('No managed installation exists')
        check_owned();command=['docker','compose','-p',PROJECT,'-f',str(STATE/'compose.json')]
        run(command+[args.operation])
        if args.operation=='restart':sync_credentials(command)
        return
    check_owned()
    if args.operation=='validate':
        document=definition({key:image_tag(key) for key in ('quickstart','device','runtime')})
        run(['docker','compose','-p',PROJECT,'-f','-','config','--quiet'],input=json.dumps(document),text=True);return
    selected=images()
    # Registry/base are installation prerequisites; no workflow data enters this path.
    command=compose(definition(selected))
    base=registry_base(command)
    command=compose(definition(selected,base))
    run(command+['up','-d','--no-recreate','--pull','never'])
    print('Blank console: http://127.0.0.1:18090/console/; existing installations preserve their profiles.',flush=True)
    sync_credentials(command)


if __name__=='__main__':
    try:main()
    except ValueError as failure:
        print(str(failure),file=sys.stderr)
        raise SystemExit(1)
    except (OSError,subprocess.CalledProcessError):
        print('Local stack operation failed; existing storage was preserved. Inspect the preceding build/service status.',file=sys.stderr)
        raise SystemExit(1)
