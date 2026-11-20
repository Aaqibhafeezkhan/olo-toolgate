# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Provision the full local Compose stack from one selected, mounted workflow configuration."""
import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import time

HERE=Path(__file__).resolve().parent
sys.path.insert(0,str(HERE/'scripts'))
import configuration
import lifecycle
import image_assets
import package_assets


def run(arguments, **options):
    return subprocess.run(arguments,cwd=options.pop('cwd',HERE),check=True,**options)


def source_root():
    candidate=HERE.parents[2] if len(HERE.parents)>2 else HERE
    if not (candidate/'tools/quickstart/check.py').is_file():
        raise ValueError('Source image builds require the repository checkout; select published images in the profile')
    return candidate


def default_configuration():
    env_file=HERE/'.env'
    if not env_file.exists():
        return ''
    defaults={}
    for line in env_file.read_text(encoding='utf-8-sig').splitlines():
        if line.strip() and not line.lstrip().startswith('#'):
            key,value=line.split('=',1)
            if key.strip()!='CONFIGURATION':
                raise ValueError('The main .env may contain only CONFIGURATION; configure the workflow profile')
            defaults[key.strip()]=value.strip().strip('"').strip("'")
    return defaults.get('CONFIGURATION','')


def environment(selection=None):
    name=selection or default_configuration()
    if not configuration.NAME.fullmatch(name):
        raise ValueError('Select a configuration directory name without paths')
    settings=configuration.load(HERE/'configurations'/name)
    state=HERE/'state'/name
    deployment=settings['deployment']
    platform,engine,monitoring=deployment['platform'],deployment['model'],deployment['monitoring']
    model_root=HERE/'models'
    model_file=model_root/engine['modelFile']
    if (model_root.is_symlink() or model_file.is_symlink() or not model_root.resolve().is_relative_to(HERE.resolve())
            or model_file.resolve().parent!=model_root.resolve()):
        raise ValueError('Model artifacts must be regular files within the bundle models directory')
    values=dict(CONFIGURATION=name,TOOLGATE_WORKFLOW_ROOT=HERE.resolve().as_posix(),
        TOOLGATE_DEVICE_HOSTNAME=lifecycle.device_hostname(HERE,name),
        TOOLGATE_QUICKSTART_IMAGE=platform['quickstartImage'],TOOLGATE_DEVICE_IMAGE=platform['deviceImage'],
        TOOLGATE_RUNTIME_IMAGE=deployment['runtime']['image'],TOOLGATE_HTTP_PORT=str(platform['httpPort']),
        TOOLGATE_TLS_PORT=str(platform['tlsPort']),TOOLGATE_REGISTRY_PORT=str(platform['registryPort']),
        TOOLGATE_WORKFLOW_LLAMA_IMAGE=engine['image'],TOOLGATE_WORKFLOW_MODEL_FILE=model_file.resolve().as_posix(),
        TOOLGATE_MODEL_ALIAS=settings['modelAlias'],TOOLGATE_MODEL_PORT=str(settings['modelPort']),
        TOOLGATE_MODEL_CONTEXT_SIZE=str(engine['contextSize']),TOOLGATE_MODEL_THREADS=str(engine['threads']),
        TOOLGATE_WORKFLOW_MODEL_ORIGINS=settings['modelDeployment']['origin'],
        TOOLGATE_MONITORING=str(monitoring['enabled']).lower(),
        TOOLGATE_INSIGHTS_MODE=monitoring['mode'] if monitoring['enabled'] else 'NONE',
        TOOLGATE_INSIGHTS_SPAN_SAMPLE_RATIO=str(monitoring['spanSampleRatio']),
        GRAFANA_ADMIN_USER=monitoring['grafanaUser'],GRAFANA_PORT=str(monitoring['grafanaPort']),
        PROMETHEUS_PORT=str(monitoring['prometheusPort']),
        LOCAL_UID=str(os.getuid() if hasattr(os,'getuid') else 1000),LOCAL_GID=str(os.getgid() if hasattr(os,'getgid') else 1000))
    state.mkdir(mode=0o700,parents=True,exist_ok=True)
    marker=state/'format.json'
    if any(state.iterdir()) and not marker.is_file():
        raise ValueError('Unsupported existing WorkflowStack state; preserve files and remove this selected stack before a fresh start')
    if marker.is_symlink() or marker.exists() and json.loads(marker.read_text())!={'formatVersion':2}:
        raise ValueError('Unsupported WorkflowStack state format')
    marker.write_text('{\"formatVersion\":2}\n',encoding='ascii')
    if monitoring['enabled']:
        custody=state/'workflow/monitoring'
        custody.mkdir(mode=0o700,parents=True,exist_ok=True)
        password=custody/(monitoring['grafanaSecret']+'.token')
        if not password.exists():
            with open(password,'x',opener=lambda path,flags:os.open(path,flags,0o600)) as stream:stream.write(secrets.token_urlsafe(32))
        values['GRAFANA_ADMIN_PASSWORD']=password.read_text(encoding='ascii').strip()
        (custody/'collector.json').write_text(json.dumps(monitoring['collectorConfig'],indent=2)+'\n',encoding='utf-8')
        values.update(INSIGHTS_COLLECTOR_IMAGE=monitoring['images']['collector'],
                      PROMETHEUS_IMAGE=monitoring['images']['prometheus'],GRAFANA_IMAGE=monitoring['images']['grafana'],
                      TEMPO_IMAGE=monitoring['images']['tempo'])
    os.environ.update(values)
    os.environ.update(TOOLGATE_LANGCHAIN_PROJECT='toolgate-workflow-'+name,
        TOOLGATE_LANGCHAIN_STATE=str(state),TOOLGATE_MODEL_ALIAS=settings['modelAlias'],
        LOCAL_UID=str(os.getuid() if hasattr(os,'getuid') else 1000),LOCAL_GID=str(os.getgid() if hasattr(os,'getgid') else 1000))
    generated=state/'compose.env'
    with open(generated,'w',encoding='utf-8',opener=lambda path,flags:os.open(path,flags,0o600)) as stream:
        for key,value in sorted(values.items()):
            value=str(value)
            if '\n' in value or '\r' in value:raise ValueError('Multiline deployment values are unsupported')
            stream.write(key+"='"+value.replace("'","\\'")+"'\n")
    generated.chmod(0o600)
    compose=['docker','compose','--env-file',str(generated),'-p','toolgate-workflow-'+name,'-f',str(HERE/'compose.yaml')]
    if monitoring['enabled']:
        compose+=['-f',str(HERE/'compose.monitoring.yaml')]
    return settings,state,compose


def modules(state,compose,assets=None):
    # Reuse the existing operator setup and signed package APIs, not the example's graph or prompts.
    operator_root=(assets or image_assets.extract(os.environ['TOOLGATE_QUICKSTART_IMAGE'],state))/'operator'
    spec=importlib.util.spec_from_file_location('configured_stack_operator',operator_root/'examples/langchain/manage.py')
    sys.path.insert(0,str(operator_root/'examples/langchain'))
    operator=importlib.util.module_from_spec(spec)
    spec.loader.exec_module(operator)
    operator.STATE=state
    operator.COMPOSE=compose
    return operator


def model_custody(operator,state):
    model=Path(os.environ['TOOLGATE_WORKFLOW_MODEL_FILE'])
    if not model.is_absolute():
        model=HERE/model
    if not model.is_file():
        raise ValueError('Place the workflow profile modelFile in WorkflowStack/models; see README.md')
    os.environ['TOOLGATE_WORKFLOW_MODEL_FILE']=str(model.resolve())
    ca_file=state/'workflow/model-ca.crt'
    if ca_file.exists():
        for name in ('model.crt','model.key','model.token'):
            if not (state/'workflow/model'/name).is_file():
                raise ValueError('Incomplete existing model custody; refusing to replace it')
        return
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes,serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.x509.oid import NameOID
    now=datetime.now(timezone.utc)
    ca_key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
    key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
    issuer=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'ToolGate local configured model CA')])
    ca=(x509.CertificateBuilder().subject_name(issuer).issuer_name(issuer).public_key(ca_key.public_key())
        .serial_number(x509.random_serial_number()).not_valid_before(now-timedelta(minutes=1))
        .not_valid_after(now+timedelta(days=30)).add_extension(x509.BasicConstraints(ca=True,path_length=0),critical=True)
        .add_extension(x509.SubjectKeyIdentifier.from_public_key(ca_key.public_key()),critical=False).sign(ca_key,hashes.SHA256()))
    cert=(x509.CertificateBuilder().subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'workflow-model')]))
        .issuer_name(issuer).public_key(key.public_key()).serial_number(x509.random_serial_number())
        .not_valid_before(now-timedelta(minutes=1)).not_valid_after(now+timedelta(days=7))
        .add_extension(x509.BasicConstraints(ca=False,path_length=None),critical=True)
        .add_extension(x509.SubjectAlternativeName([x509.DNSName('workflow-model')]),critical=False)
        .add_extension(x509.SubjectKeyIdentifier.from_public_key(key.public_key()),critical=False)
        .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(ca_key.public_key()),critical=False)
        .sign(ca_key,hashes.SHA256()))
    operator.shared.private_file(state/'workflow/model/model.key',key.private_bytes(serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,serialization.NoEncryption()).decode())
    operator.shared.private_file(state/'workflow/model/model.crt',cert.public_bytes(serialization.Encoding.PEM).decode())
    operator.shared.private_file(state/'workflow/model/model.token',secrets.token_urlsafe(48))
    ca_file.parent.mkdir(parents=True,exist_ok=True)
    ca_file.write_bytes(ca.public_bytes(serialization.Encoding.PEM))
    ca_file.chmod(0o644)


def qualify_reader(operator,config,state):
    destination=state/'workflow/qualified-readers.env'
    prefix=operator.linux_status()['deviceId']+'@'+operator.DEVICE_GROUP+'@'
    if destination.exists():
        # Existing current-schema custody pins the reviewed, installed release.
        # Rebuilding an SDK must not republish/replace that immutable version.
        expected='TOOLGATE_CONTROL_QUALIFIED_DEVICE_FILE_READERS='+prefix
        saved=destination.read_text(encoding='utf-8').strip()
        if not saved.startswith(expected) or not re.fullmatch(r'sha256:[a-f0-9]{64}',saved[len(expected):]):
            raise ValueError('Existing file-reader qualification does not match the current device/group')
        return
    release=operator.packages.publish(config,state/'package/hotfolder.olotool')
    pin=prefix+release['image'].rsplit('@',1)[1]
    operator.shared.private_file(destination,'TOOLGATE_CONTROL_QUALIFIED_DEVICE_FILE_READERS='+pin+'\n')


def verify_deployment(operator,state):
    saved=json.loads((state/'deployment.json').read_text(encoding='utf-8'))
    ca=operator.docker('quickstart',['cat','/data/keys/device-ca.crt'])
    if saved!={'deviceId':operator.linux_status()['deviceId'],
            'gateway':operator.gateway_url(),'caSha256':hashlib.sha256(ca.encode()).hexdigest()}:
        raise ValueError('Saved deployment does not match the current device and Gateway')


def provision(operator,settings,state):
    if settings['fileAgent'] is not None:
        operator.provision(argparse.Namespace(bootstrap_local=True,approve_linux_device=True,target='linux',device_id=None))
    else:
        config=operator.Configuration(operator.wait_ready(),True)
        config.presets()
        group=settings['deployment']['source']['deviceGroupId']
        config.upsert('device-groups',dict(id=group,name=group,revision=1,enabled=True,deviceIds=[]))
        operator.DEVICE_GROUP=group
        operator.register_devices(config,argparse.Namespace(approve_linux_device=True,target='linux',device_id=None))
        operator.configure_package_device()
        operator.wait_linux_status(timeout=120)
        operator.sync_deployment_metadata()
    ca=operator.docker('quickstart',['cat','/data/keys/device-ca.crt'])
    operator.shared.private_file(state/'agents/gateway-ca.crt',ca)
    operator.shared.private_file(state/'deployment.json',json.dumps(dict(deviceId=operator.linux_status()['deviceId'],
        gateway=operator.gateway_url(),caSha256=hashlib.sha256(ca.encode()).hexdigest())))


def prepare_packages(operator,settings,state,compose,assets):
    source=settings['deployment']['source']
    metadata,artifact=package_assets.select(source,HERE/'configurations'/settings['id'],assets)
    directory=state/'package/bootstrap'
    if (state/'deployment.json').exists():
        lock=json.loads((directory/'core-packages.lock.json').read_text(encoding='utf-8'))
        if lock.get('schemaVersion')!=1 or len(lock.get('packages',[]))!=1:
            raise ValueError('Existing selected trigger package custody is unsupported')
        saved=lock['packages'][0]
        path=directory/saved['artifact']
        if (saved['id'],saved['version'],saved['packageDigest'],saved['artifactSha256']) != (
                metadata['id'],metadata['version'],metadata['packageDigest'],metadata['artifactSha256']):
            raise ValueError('Selected trigger package changed; preserve state and use a fresh stack')
        if path.is_symlink() or hashlib.sha256(path.read_bytes()).hexdigest()!=saved['artifactSha256']:
            raise ValueError('Existing selected trigger package custody does not match its sealed lock')
        run(compose+['up','-d','registry'])
        return
    directory.mkdir(mode=0o700,parents=True,exist_ok=True)
    target=dict(metadata,artifact='trigger.olotool')
    shutil.copyfile(artifact,directory/target['artifact'])
    lock=dict(schemaVersion=1,sdkVersion=metadata['sdkVersion'],packages=[dict(
        project='profile-selected-package',sdk='java-gradle',**{key:target[key] for key in
            ('id','version','artifact','artifactSha256','packageDigest')})])
    (directory/'core-packages.lock.json').write_text(json.dumps(lock,indent=2)+'\n',encoding='utf-8')
    if settings['fileAgent'] is not None:
        # This optional file-delivery adapter has its own tool package. It does
        # not determine the watch package, trigger configuration or event filter.
        shutil.copyfile(assets/'packages/hotfolder.olotool',state/'package/hotfolder.olotool')
    operator.packages.registry(compose,state,os.environ)


def bootstrap_profile(settings,state):
    source=settings['deployment']['source']
    document=dict(profileVersion=2,profileId='stack-bootstrap-'+hashlib.sha256(settings['workflowId'].encode()).hexdigest()[:16],
        packages=[dict(packageId=source['packageId'],source='PREBUILT_CORE',version=source['version'],required=True,
            deviceGroupIds=[source['deviceGroupId']],triggers=[dict(assignment=source['assignment'],
                triggerId=source['triggerId'],config=source['config'])])])
    (state/'package/workflow-bootstrap.json').write_text(json.dumps(document,indent=2)+'\n',encoding='utf-8')


def wait_trigger(config,settings,timeout=120):
    deadline=time.monotonic()+timeout
    while True:
        try:assignment=config.api('/api/control/v1/triggers/assignments/'+settings['assignment'])
        except ValueError as failure:
            if 'HTTP 404' not in str(failure):raise
            assignment={}
        status=assignment.get('status',{})
        if (status.get('observedGeneration')==assignment.get('metadata',{}).get('generation')
                and any(item.get('type')=='Available' and item.get('status')=='True'
                        for item in status.get('conditions',[]))):
            return
        if time.monotonic()>=deadline:
            raise ValueError('Configured trigger is not available; inspect its instance/package conditions')
        time.sleep(2)


def profile(operator,settings,state):
    config=operator.Configuration(operator.wait_ready(),True)
    wait_trigger(config,settings,timeout=360)
    try:
        config.api('/api/control/v1/triggers/subscriptions/'+settings['subscription'])
    except ValueError as failure:
        if 'HTTP 404' not in str(failure):
            raise
        config.api('/api/control/v1/triggers/subscriptions',dict(name=settings['subscription'],spec=dict(
            sources=dict(assignments=[settings['assignment']]),filter=settings['deployment']['source']['eventFilter'],
            expiresAtUnixMs=int(time.time()*1000)+30*86400000)))
    declared=config.api('/api/control/v1/triggers/subscriptions/'+settings['subscription'])
    if (declared['spec']['filter']!=settings['deployment']['source']['eventFilter']
            or declared['spec']['sources']!={'assignments':[settings['assignment']]}):
        raise ValueError('Subscription declaration changed; use a new subscription name and workflow version')
    subscription=declared['metadata']['uid']
    assignment=config.api('/api/control/v1/triggers/assignments/'+settings['assignment'])['metadata']['uid']
    document=json.loads(json.dumps(settings['document']))
    document['workflows']['dispatchBindings']=configuration.resolve(document['workflows']['dispatchBindings'],
        {'${SUBSCRIPTION_UID}':subscription,'${ASSIGNMENT_UID}':assignment})
    workflows=document['workflows']
    # Mounted JSON bootstraps a fresh installation. Restarting a configured stack
    # must not overwrite workflow data subsequently managed through Control.
    after=''
    for _ in range(21):
        published=config.api('/api/control/v1/workflow-profiles/published?limit=50'+('&afterProfileId='+after if after else ''))
        head=next((item for item in published if item['profileId']==document['profileId']),None)
        if head:
            operator.shared.private_file(state/'workflow/reader/delivery.json',json.dumps(dict(
                profileId=head['profileId'],profileDigest=head['digest'],workflowIds=[settings['workflowId']])))
            operator.shared.private_file(state/'workflow/worker/listener.json',json.dumps(dict(name=settings['subscription'],uid=subscription)))
            if settings['fileAgent'] is not None:qualify_reader(operator,config,state)
            return config
        if len(published)<50:break
        after=published[-1]['profileId']
    else:raise ValueError('Workflow publication catalog exceeds the bootstrap lookup limit')
    # Each declared agent/group is independently administered. Only the original file agent has MCP grants.
    role='configured-workflow-service'
    config.upsert('roles',dict(id=role,name='Configured workflow service',revision=1,enabled=True,
        portalRole='BASIC',roleType='ACTOR_SERVICE',managementRules=[]))
    groups={}
    for workflow in workflows['workflows']:
        for binding in workflow['agents']:
            groups.setdefault(binding['agentGroupId'],set()).add(binding['agentId'])
    existing_groups={item['id']:item for item in config.all('agent-groups')}
    existing_agents={item['id'] for item in config.all('agents')}
    for group,agents in groups.items():
        for agent in agents-existing_agents:
            config.upsert('agents',dict(id=agent,name=agent,revision=1,enabled=True,ownerUserId=config.username))
        old=existing_groups.get(group,{})
        config.upsert('agent-groups',{**old,'id':group,'name':old.get('name',group),'revision':old.get('revision',1),'enabled':True,
            'agentIds':sorted(set(old.get('agentIds',[]))|agents),'roleIds':sorted(set(old.get('roleIds',[]))|{role})})
    base=config.api('/api/control/v1/grants/standard-ReadAndWrite-capability')['scope']
    resources=sorted({'workflow-group:'+item['workflowGroupId'] for item in workflows['workflows']}|
        {'model-group:'+item['id'] for item in workflows['modelGroups']})
    scope={**base,'toolGroups':dict(ids=[],all=True),'deviceGroups':dict(ids=[],all=True),'allActions':False,
        'actions':['workflow.execute','model.infer'],'resources':[dict(kind='CUSTOM',match='EXACT',locator=item) for item in resources],
        'conditions':{**base['conditions'],'requireOnline':False,'highRisk':False}}
    config.upsert('grants',dict(id=role,name='Configured workflow authority',revision=1,enabled=True,
        sourceType='ROLE',sourceId=role,purpose='SERVICE',scope=scope))
    for model in workflows['modelDeployments']:
        if model['id']==settings['modelDeployment']['id']:
            config.api('/api/control/v1/vault',dict(name=model['secretName'],value=(state/'workflow/model/model.token').read_text().strip(),
                toolGroupId=model['toolGroupId'],deviceGroupId=model['deviceGroupId']))
    base='/api/control/v1/workflow-profiles'
    staged=config.api(base,document)
    digest={'profileDigest':staged['profileDigest']}
    config.api(base+'/'+document['profileId']+'/review',digest)
    if document.get('packages'):
        config.api(base+'/'+document['profileId']+'/provision',digest)
        deadline=time.monotonic()+300
        while not config.api(base+'/'+document['profileId']+'/revisions/'+staged['profileDigest'])['dependenciesReady']:
            if time.monotonic()>deadline:
                raise ValueError('Configured profile dependencies did not become ready; inspect provisioning status')
            time.sleep(2)
    config.api(base+'/'+document['profileId']+'/publish',{**digest,'expectedGeneration':staged['generation']})
    operator.shared.private_file(state/'workflow/reader/delivery.json',json.dumps(dict(profileId=document['profileId'],profileDigest=staged['profileDigest'],
        workflowIds=[item['id'] for item in workflows['workflows']])))
    operator.shared.private_file(state/'workflow/worker/listener.json',json.dumps(dict(name=settings['subscription'],uid=subscription)))
    if settings['fileAgent'] is not None:qualify_reader(operator,config,state)
    return config


def deploy(settings,state,compose,build):
    state.mkdir(mode=0o700,parents=True,exist_ok=True)
    if build:
        run([sys.executable,'tools/quickstart/check.py','--build','--build-only','--image',os.environ['TOOLGATE_QUICKSTART_IMAGE']],cwd=source_root())
        run(['docker','build','-t',os.environ['TOOLGATE_RUNTIME_IMAGE'],'apps/langgraph-runtime'],cwd=source_root())
    run(['docker','run','--rm','--network','none','--read-only','--cap-drop','ALL',
         '-v',str((HERE/'configurations'/settings['id']).resolve())+':/configuration:ro',
         '--entrypoint','/opt/toolgate-runtime/bin/python',os.environ['TOOLGATE_RUNTIME_IMAGE'],
         '-m','toolgate_runtime.graph_packages','--profile','/configuration/workflow-profile.json',
         '--workflow',settings['workflowId'],'--graph-directory','/configuration/graphs'])
    assets=image_assets.extract(os.environ['TOOLGATE_QUICKSTART_IMAGE'],state)
    operator=modules(state,compose,assets)
    model_custody(operator,state)
    prepare_packages(operator,settings,state,compose,assets)
    bootstrap_profile(settings,state)
    if os.environ.get('TOOLGATE_MONITORING','false').lower()=='true':
        run(compose+['up','-d','insights-collector','prometheus','tempo','grafana'])
    run(compose+['up','-d','--force-recreate','--wait','--wait-timeout','240','quickstart','linux-device','workflow-model'])
    if (state/'deployment.json').exists():
        try:verify_deployment(operator,state)
        except ValueError:
            raise ValueError('Saved deployment credentials do not match the current Gateway. '
                'Run destroy.bat --configuration '+settings['id']+' to remove its generated storage, '
                'then start.bat --configuration '+settings['id']+'. This deletes that configuration state/data.') from None
    else:
        provision(operator,settings,state)
    config=profile(operator,settings,state)
    exchange=HERE/'data'/settings['id']
    for folder in (settings['fileAgent'] or {}).values():
        if isinstance(folder,str) and folder in (settings['fileAgent'][key] for key in ('inbox','processed','output')):
            (exchange/folder).mkdir(mode=0o700,parents=True,exist_ok=True)
    (state/'workflow/results').mkdir(mode=0o700,parents=True,exist_ok=True)
    (state/'workflow/reader').mkdir(mode=0o700,parents=True,exist_ok=True)
    run(compose+['up','-d','--no-deps','quickstart'])
    operator.wait_ready()
    device=operator.linux_status()['deviceId']
    run(compose+['restart','linux-device'])
    reconnected_after=int(time.time()*1000)
    operator.wait_device(config,device,seen_after=reconnected_after,timeout=360)
    wait_trigger(config,settings)
    started=int(time.time())
    run(compose+['up','-d','--force-recreate','identity'])
    deadline=time.monotonic()+90
    while (not (state/'workflow/reader/result.token').is_file()
            or not (state/'workflow/identity-ready').is_file()
            or int((state/'workflow/identity-ready').read_text())<started):
        if time.monotonic()>deadline:
            raise ValueError('Identity service did not become ready; inspect its metadata logs')
        time.sleep(1)
    services=['workflow-worker','workflow-listener']+(['folder-agent'] if settings['fileAgent'] is not None else [])
    run(compose+['up','-d','--no-deps','--force-recreate',*services])
    print('Configuration: '+settings['id'])
    print('Console: http://127.0.0.1:'+os.environ['TOOLGATE_HTTP_PORT']+'/console/')
    if settings['fileAgent'] is not None:
        print('Drop .txt files in: '+str(exchange/settings['fileAgent']['inbox']))
        print('Outputs: '+str(exchange/settings['fileAgent']['output']))
    print('Admin credentials: '+str(state/'credentials/admin'))


def ensure_start_images():
    for variable,component in [('TOOLGATE_QUICKSTART_IMAGE','quickstart'),('TOOLGATE_RUNTIME_IMAGE','runtime')]:
        reference=os.environ[variable]
        if subprocess.run(['docker','image','inspect',reference],capture_output=True).returncode==0:
            continue
        if '@sha256:' in reference or '/' in reference:
            run(['docker','pull',reference])
        elif component=='quickstart':
            run([sys.executable,'tools/quickstart/check.py','--build','--build-only','--image',reference],cwd=source_root())
        else:
            run(['docker','build','-t',reference,'apps/langgraph-runtime'],cwd=source_root())


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=['start','admin','deploy','stop','destroy','status','logs'])
    parser.add_argument('--configuration')
    parser.add_argument('--blank',action='store_true',help='Start the admin UI with a blank installation')
    parser.add_argument('--build',action='store_true',help='Build current source images before provisioning')
    args=parser.parse_args()
    try:
        if args.command in ('start','admin'):
            if args.build:raise ValueError('Build images with manage.py deploy --build --configuration <name> before starting the admin UI')
            import admin_server
            selection=None if args.blank else (args.configuration or default_configuration() or None)
            if selection and not (HERE/'configurations'/selection/'workflow-profile.json').is_file():
                if args.configuration:raise ValueError('Selected configuration does not exist')
                selection=None
            if args.command=='admin':
                admin_server.serve(HERE,selection)
                return
            # Keep the privileged operator outside containers, with a hidden background process.
            import urllib.request
            try:
                with urllib.request.urlopen(admin_server.ORIGIN+'/operator',timeout=2) as response:
                    response.read(1)
            except OSError:
                home=HERE/'state/administrator'
                home.mkdir(mode=0o700,parents=True,exist_ok=True)
                arguments=[sys.executable,str(HERE/'manage.py'),'admin']
                arguments+=['--configuration',selection] if selection else ['--blank']
                with open(home/'server.log','ab') as log:
                    subprocess.Popen(arguments,cwd=HERE,stdin=subprocess.DEVNULL,stdout=log,stderr=log,
                        creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0,
                        start_new_session=os.name!='nt')
                deadline=time.monotonic()+20
                while time.monotonic()<deadline:
                    try:
                        with urllib.request.urlopen(admin_server.ORIGIN+'/operator',timeout=1) as response:response.read(1)
                        break
                    except OSError:time.sleep(.2)
                else:raise ValueError('Administrator UI did not start; inspect state/administrator/server.log')
            print('WorkflowStack administration: '+admin_server.ORIGIN)
            print('Control admin credentials: '+str(HERE/'state'/(selection or 'blank')/'credentials/admin'))
            print('Advanced operator credentials: '+str(HERE/'state/administrator/credentials/admin'))
            print('Startup runs in the background; inspect its status in the administration UI.')
            return
        if args.command=='destroy':
            if not args.configuration:
                import admin_server
                admin_server.shutdown(HERE)
            lifecycle.destroy(HERE,args.configuration)
            return
        settings,state,compose=environment(args.configuration)
        if args.command=='deploy':
            if not args.build:
                if not Path(os.environ['TOOLGATE_WORKFLOW_MODEL_FILE']).is_file():
                    raise ValueError('Place the workflow profile modelFile in WorkflowStack/models before startup')
                ensure_start_images()
            deploy(settings,state,compose,args.build)
        elif args.command=='stop':
            run(compose+['stop'])
        elif args.command=='status':
            run(compose+['ps'])
        else:
            run(compose+['logs','--tail','80'])
    except (ValueError,OSError,subprocess.SubprocessError) as failure:
        parser.exit(1,'Deployment failed: '+(str(failure) if isinstance(failure,ValueError) else 'inspect bounded service logs')+'\n')
    except KeyboardInterrupt:
        parser.exit(130,'Cancelled.\n')


if __name__=='__main__':
    main()
