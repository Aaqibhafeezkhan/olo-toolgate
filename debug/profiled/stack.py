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

SOURCE=Path(__file__).resolve().parent
ROOT=SOURCE.parents[1]
HERE=Path(os.environ.get('OLO_STACK_HOME',str(SOURCE))).resolve()
STATE=HERE/'state'
PROJECT=os.environ.get('OLO_STACK_PROJECT','toolgate-profiled')
HTTP_PORT=int(os.environ.get('OLO_STACK_HTTP_PORT','18090'))
TLS_PORT=int(os.environ.get('OLO_STACK_TLS_PORT','18450'))
REGISTRY_PORT=int(os.environ.get('OLO_STACK_REGISTRY_PORT','18552'))
if not all(1024<=port<=65535 for port in (HTTP_PORT,TLS_PORT,REGISTRY_PORT)) or len({HTTP_PORT,TLS_PORT,REGISTRY_PORT})!=3:
    raise ValueError('Use three distinct unprivileged installation ports')
if not __import__('re').fullmatch('[a-z][a-z0-9-]{0,62}',PROJECT):raise ValueError('Invalid installation project')
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
    if module=='quickstart':fixed|={'tools/ci/gradle_build.py','deploy/profiles/fresh.json','debug/profiled/configure-debug-agent.py',
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


def images(*,validate_only=False):
    if os.environ.get('OLO_STACK_IMAGE_MODE')=='published':
        import re
        selected={key:os.environ.get('OLO_STACK_'+key.upper()+'_IMAGE','') for key in ('quickstart','device','runtime')}
        for image in selected.values():
            if not re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9./_:@-]{0,511}',image) or (':' not in image and '@sha256:' not in image):
                raise ValueError('Set explicit application image tags or digests')
            tag=image.rsplit(':',1)[-1]
            mutable='@' not in image and (tag=='dev' or tag.startswith('dev-'))
            if not validate_only and (mutable or not image_exists(image)):run(['docker','pull',image])
        return selected
    selected={module:image_tag(module) for module in ('quickstart','device','runtime')}
    if validate_only:return selected
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


def secure_directory(folder):
    folder.mkdir(parents=True,exist_ok=True,mode=0o700)
    if os.name=='nt':
        account=run(['whoami','/user','/fo','csv','/nh'],capture_output=True,text=True).stdout
        sid=next(csv.reader(account.strip().splitlines()))[-1]
        if not sid.startswith('S-1-') or any(c not in 'S-0123456789' for c in sid):raise ValueError('Cannot resolve private-folder owner')
        run(['icacls',str(folder),'/inheritance:r','/grant:r','*'+sid+':(OI)(CI)F'],capture_output=True,text=True)
    else:folder.chmod(0o700)


def private(path,data):
    secure_directory(path.parent)
    with open(path,'x',encoding='utf-8',opener=lambda p,f:os.open(p,f,0o600)) as stream:stream.write(data)


def compose(document):
    path=STATE/'compose.json'
    path.write_text(json.dumps(document,indent=2)+'\n',encoding='utf-8')
    return ['docker','compose','-p',PROJECT,'-f',str(path)]


def sync_credentials(command,timeout=180):
    folder=HERE/'credentials/admin'
    password_file=folder/'password.txt'
    candidate=password_file.read_text(encoding='utf-8-sig').rstrip('\r\n') if password_file.exists() else None
    script=(SOURCE/'credential-state.py').read_text(encoding='utf-8')
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
    owner={'io.ololabs.toolgate.profiled-root':str(HERE),'io.ololabs.toolgate.profiled-format':'2'}
    control=f'https://localhost:{TLS_PORT}'
    secure=dict(read_only=True,cap_drop=['ALL'],security_opt=['no-new-privileges:true'])
    services={
        'quickstart':dict(image=selected['quickstart'],user='0:0',
            entrypoint=['/opt/quickstart-python/bin/python','/opt/quickstart/workflow_installation.py'],
            ports=[f'127.0.0.1:{HTTP_PORT}:8080',f'127.0.0.1:{TLS_PORT}:8443',f'127.0.0.1:{REGISTRY_PORT}:18552'],
            environment={'TOOLGATE_QUICKSTART_WORKFLOW_STACK':'true','TOOLGATE_QUICKSTART_ADMIN_ONLY':'true','TOOLGATE_DISABLE_ADMIN_PASSWORD':'false',
                'TOOLGATE_CONTROL_PROFILE_PATH':'','TOOLGATE_CONTROL_CORE_PACKAGES_LOCK_PATH':'',
                'TOOLGATE_TRIGGERS_CONTROL_CORE_ENABLED':'true',
                'TOOLGATE_QUICKSTART_DATABASE_MODE':'sqlite','TOOLGATE_QUICKSTART_CLIENT_CREDENTIALS':'true',
                'TOOLGATE_CONTROL_ENDPOINT_CONTROL_URL':control,'TOOLGATE_CONTROL_ENDPOINT_GATEWAY_URL':control,
                'TOOLGATE_CONTROL_ENDPOINT_CONSOLE_URL':f'http://127.0.0.1:{HTTP_PORT}',
                'TOOLGATE_CONTROL_RUNTIME_BUILDER_ORIGIN':'https://localhost:19460',
                'TOOLGATE_CONTROL_RUNTIME_BUILDER_IDENTITY_PATH':'/custody/control/identity.p12',
                'TOOLGATE_CONTROL_RUNTIME_BUILDER_PASSWORD_PATH':'/custody/control/password',
                'TOOLGATE_CONTROL_RUNTIME_BUILDER_CA_PATH':'/custody/control/ca.crt',
                'TOOLGATE_CONTROL_CLIENT_IMAGES_ENABLED':'true',
                'TOOLGATE_CONTROL_CLIENT_IMAGES_REGISTRY_URL':'http://localhost:18552',
                'TOOLGATE_CONTROL_CLIENT_IMAGES_REPOSITORY':'toolgate/client-tools',
                'TOOLGATE_CONTROL_CLIENT_IMAGES_BASE':base or 'toolgate/java-base@sha256:'+'0'*64,
                'TOOLGATE_CONTROL_CLIENT_IMAGES_PLATFORMS':'LINUX'},
            volumes=['data:/data','custody:/custody','registry:/registry'],tmpfs=['/tmp:rw,noexec,nosuid,size=128m,uid=65532,gid=65532'],
            restart='unless-stopped',stop_grace_period='35s',cap_add=['CHOWN','FOWNER','DAC_OVERRIDE','SETUID','SETGID'],**secure),
        'device':dict(image=selected['device'],hostname=PROJECT+'-device',
            entrypoint=['python3','-u','/opt/toolgate/installation_device.py'] if os.environ.get('OLO_STACK_IMAGE_MODE')=='published' else None,
            depends_on={'quickstart':{'condition':'service_healthy'}},
            environment={'TOOLGATE_DEVICE_UPSTREAM':'quickstart','TOOLGATE_DEVICE_HTTP_PORT':str(HTTP_PORT),
                         'TOOLGATE_DEVICE_TLS_PORT':str(TLS_PORT),'TOOLGATE_DEVICE_WAIT_TIMEOUT':'0'},
            volumes=['device-config:/etc/olo-toolgate','device-state:/var/lib/olo-toolgate','files:/var/lib/olo-toolgate/files',
                     dict(type='volume',source='custody',target='/installation-trust',read_only=True,volume=dict(subpath='device')),
                     '/var/run/docker.sock:/var/run/docker.sock'],
            tmpfs=['/run:rw,nosuid,size=8m,mode=755'],cap_drop=['ALL'],security_opt=['no-new-privileges:true'],restart='unless-stopped'),
        'langgraph':dict(image=selected['runtime'],network_mode='service:quickstart',
            depends_on={'quickstart':{'condition':'service_healthy'}},
            command=['--configuration','/installation/service.json'],
            volumes=[dict(type='volume',source='custody',target='/installation',read_only=True,volume=dict(subpath='langgraph'))],
            tmpfs=['/tmp:rw,noexec,nosuid,size=64m,uid=65532,gid=65532'],
            restart='unless-stopped',stop_grace_period='35s',**secure)}
    settings=HERE/'installation.env'
    if settings.exists():services['quickstart']['env_file']=[str(settings)]
    runtime_observability=HERE/'credentials/runtime-observability.env'
    if runtime_observability.exists():services['langgraph']['env_file']=[str(runtime_observability)]
    for service in services.values():service['labels']=owner
    return dict(services=services,volumes={name:dict(labels=owner,name=storage_prefix()+'_'+name) for name in
                ('registry','data','custody','device-config','device-state','files')},networks={'default':dict(labels=owner)})


def registry_base(command):
    run(command+['up','-d','--no-recreate','--pull','never','quickstart'])
    deadline=time.monotonic()+90
    while True:
        try:
            with urllib.request.urlopen(f'http://localhost:{REGISTRY_PORT}/v2/',timeout=2):break
        except OSError:
            if time.monotonic()>deadline:raise ValueError('Embedded registry unavailable')
            time.sleep(1)
    if not image_exists(BASE):run(['docker','pull','--platform','linux/amd64',BASE])
    target=f'localhost:{REGISTRY_PORT}/toolgate/java-base:approved'
    run(['docker','tag',BASE,target]);run(['docker','push',target])
    request=urllib.request.Request(f'http://localhost:{REGISTRY_PORT}/v2/toolgate/java-base/manifests/approved',
        headers={'Accept':'application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json'})
    with urllib.request.urlopen(request,timeout=20) as response:
        return 'toolgate/java-base@sha256:'+hashlib.sha256(response.read()).hexdigest()


def provision_runtime(command):
    """External operator action: backend API issuance, then private file delivery; never changes Admin password."""
    folder=HERE/'credentials/admin'
    password=(folder/'password.txt').read_text(encoding='utf-8-sig').rstrip('\r\n')
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    def request(path,body,token=None):
        headers={'Content-Type':'application/json'}
        if token:headers['Authorization']='Bearer '+token
        query=urllib.request.Request(f'http://127.0.0.1:{HTTP_PORT}'+path,json.dumps(body).encode(),headers,method='POST')
        with opener.open(query,timeout=20) as response:return json.load(response)
    deadline=time.monotonic()+180
    while True:
        try:
            with opener.open(f'http://127.0.0.1:{HTTP_PORT}/health/ready',timeout=3) as response:
                if response.status==200:break
        except OSError:
            if time.monotonic()>deadline:raise ValueError('Quickstart API readiness unavailable; credentials preserved')
            time.sleep(1)
    token=request('/api/quickstart/v1/login',{'username':'admin','password':password})['accessToken']
    credential=request('/api/quickstart/v1/runtime-credential',{},token)
    run(command+['exec','-T','--user','0:0','quickstart','/opt/quickstart-python/bin/python',
        '/opt/quickstart/workflow_installation.py','provision'],input=json.dumps(credential),text=True)
    print('Runtime service credential provisioned by operator API script; Admin password unchanged.',flush=True)


def operator_client():
    from tools.admin.control import Control
    folder=HERE/'credentials/admin'
    password=(folder/'password.txt').read_text(encoding='utf-8-sig').rstrip('\r\n')
    request=urllib.request.Request(f'http://127.0.0.1:{HTTP_PORT}/api/quickstart/v1/login',
        json.dumps(dict(username='admin',password=password)).encode(),{'Content-Type':'application/json'},method='POST')
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(request,timeout=20) as response:token=json.load(response)['accessToken']
    pending=folder/('operator.'+uuid.uuid4().hex+'.next');private(pending,token)
    path=folder/'operator.token';os.replace(pending,path)
    return Control(f'https://127.0.0.1:{TLS_PORT}',path,folder/'gateway-ca.crt')


def check_owned():
    raw=run(['docker','volume','ls','--filter','name='+storage_prefix()+'_','--format','{{.Name}}'],capture_output=True,text=True).stdout
    for name in raw.splitlines():
        item=json.loads(run(['docker','volume','inspect',name],capture_output=True,text=True).stdout)[0]
        labels=item.get('Labels') or {}
        if labels.get('io.ololabs.toolgate.profiled-root')!=str(HERE) or labels.get('io.ololabs.toolgate.profiled-format')!='2':
            raise ValueError('Existing volume is unsupported or belongs elsewhere; preserved: '+name)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation',choices=['start','stop','restart','validate','provision-runtime'])
    parser.add_argument('--fresh',action='store_true',help='Select fresh installation storage; preserve obsolete stores and manifests')
    args=parser.parse_args()
    if args.fresh and args.operation!='start':parser.error('--fresh applies only to start')
    try:engine=subprocess.run(['docker','info','--format','{{.OSType}}'],capture_output=True,text=True)
    except FileNotFoundError:raise ValueError('Install Docker Desktop and start its Linux container engine, then run start.bat again.') from None
    if engine.returncode!=0 or engine.stdout.strip()!='linux':
        raise ValueError('Start Docker Desktop with its Linux container engine, then run start.bat again.')
    STATE.mkdir(parents=True,exist_ok=True)
    if args.fresh:
        existing=run(['docker','ps','-aq','--filter','label=com.docker.compose.project='+PROJECT],capture_output=True,text=True).stdout.strip()
        if existing:raise ValueError('Stop and remove the selected project containers explicitly before selecting fresh storage; volumes preserved')
        previous=STATE/'history'/uuid.uuid4().hex;previous.mkdir(parents=True,mode=0o700)
        for name in ('compose.json','storage.json'):
            path=STATE/name
            if path.exists():(previous/name).write_bytes(path.read_bytes())
        (STATE/'storage.json').write_text(json.dumps(dict(formatVersion=1,prefix=PROJECT+'-three-'+uuid.uuid4().hex))+'\n',encoding='utf-8')
        # New composition only; the previous manifest remains available for explicit operator recovery.
        selected=images()
        compose(definition(selected))
    if args.operation=='provision-runtime':
        check_owned();provision_runtime(['docker','compose','-p',PROJECT,'-f',str(STATE/'compose.json')]);return
    if args.operation in ('stop','restart'):
        if not (STATE/'compose.json').exists():raise ValueError('No managed installation exists')
        check_owned();command=['docker','compose','-p',PROJECT,'-f',str(STATE/'compose.json')]
        run(command+[args.operation])
        if args.operation=='restart':
            sync_credentials(command);provision_runtime(command)
        return
    if args.operation=='validate':
        document=definition(images(validate_only=True))
        run(['docker','compose','-p',PROJECT,'-f','-','config','--quiet'],input=json.dumps(document),text=True);return
    check_owned()
    existing=STATE/'compose.json'
    if existing.exists() and set(json.loads(existing.read_text(encoding='utf-8'))['services'])!={'quickstart','langgraph','device'}:
        raise ValueError('Obsolete stack definition; preserve existing storage and select a fresh installation before starting')
    selected=images()
    # Registry/base are installation prerequisites; no workflow data enters this path.
    command=compose(definition(selected))
    base=registry_base(command)
    run(command+['exec','-T','--user','0:0','quickstart','/opt/quickstart-python/bin/python',
        '/opt/quickstart/workflow_installation.py','base'],input=base,text=True)
    sync_credentials(command)
    provision_runtime(command)
    command=compose(definition(selected,base))
    run(command+['up','-d','--no-recreate','--pull','never'])
    print(f'Blank console: http://127.0.0.1:{HTTP_PORT}/console/; existing installations preserve their profiles.',flush=True)
    sync_credentials(command)


if __name__=='__main__':
    try:main()
    except ValueError as failure:
        print(str(failure),file=sys.stderr)
        raise SystemExit(1)
    except (OSError,subprocess.CalledProcessError):
        print('Local stack operation failed; existing storage was preserved. Inspect the preceding build/service status.',file=sys.stderr)
        raise SystemExit(1)
