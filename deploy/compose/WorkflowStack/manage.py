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
ROOT=HERE.parents[2]
sys.path.insert(0,str(HERE/'scripts'))
import configuration
import lifecycle


def run(arguments, **options):
    return subprocess.run(arguments,cwd=ROOT,check=True,**options)


def default_configuration():
    env_file=HERE/'.env'
    if not env_file.exists():
        shutil.copyfile(HERE/'.env.example',env_file)
    defaults={}
    for line in env_file.read_text(encoding='utf-8-sig').splitlines():
        if line.strip() and not line.lstrip().startswith('#'):
            key,value=line.split('=',1)
            if key.strip()!='CONFIGURATION':
                raise ValueError('The main .env may contain only CONFIGURATION; configure the workflow profile')
            defaults[key.strip()]=value.strip().strip('"').strip("'")
    return defaults.get('CONFIGURATION','issue-triage')


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


def modules(state,compose):
    # Reuse the existing operator setup and signed package APIs, not the example's graph or prompts.
    spec=importlib.util.spec_from_file_location('configured_stack_operator',ROOT/'examples/langchain/manage.py')
    sys.path.insert(0,str(ROOT/'examples/langchain'))
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
    release=operator.packages.publish(config,state/'package/hotfolder.tgpkg')
    pin=prefix+release['image'].rsplit('@',1)[1]
    operator.shared.private_file(destination,'TOOLGATE_CONTROL_QUALIFIED_DEVICE_FILE_READERS='+pin+'\n')


def prepare_packages(operator,state,compose):
    if not (state/'agents/linux.json').exists():
        operator.packages.prepare(compose,state,os.environ)
        return
    # Recovery uses the sealed current deployment, never a rebuild under an
    # already published package version. Refuse incomplete/altered custody.
    lock=state/'package/core-packages.lock.json'
    artifact=state/'package/inbox-watch.tgpkg'
    if not lock.is_file() or not artifact.is_file() or not (state/'client-images.env').is_file():
        raise ValueError('Existing deployment package custody is incomplete')
    document=json.loads(lock.read_text(encoding='utf-8'))
    entries=document.get('packages')
    if (document.get('schemaVersion')!=1 or not isinstance(entries,list) or len(entries)!=1
            or entries[0].get('id')!='examples.inbox-watch' or entries[0].get('artifact')!=artifact.name
            or entries[0].get('artifactSha256')!=hashlib.sha256(artifact.read_bytes()).hexdigest()):
        raise ValueError('Existing deployment package custody does not match its sealed lock')
    run(compose+['up','-d','registry'])


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
        assignment=config.api('/api/control/v1/triggers/assignments/'+settings['assignment'])
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
    try:
        config.api('/api/control/v1/triggers/subscriptions/'+settings['subscription'])
    except ValueError as failure:
        if 'HTTP 404' not in str(failure):
            raise
        config.api('/api/control/v1/triggers/subscriptions',dict(name=settings['subscription'],spec=dict(
            sources=dict(assignments=[settings['assignment']]),filter="event.type == 'examples.files.observed.v1'",
            expiresAtUnixMs=int(time.time()*1000)+30*86400000)))
    subscription=config.api('/api/control/v1/triggers/subscriptions/'+settings['subscription'])['metadata']['uid']
    assignment=config.api('/api/control/v1/triggers/assignments/'+settings['assignment'])['metadata']['uid']
    document=json.loads(json.dumps(settings['document']))
    document['workflows']['dispatchBindings']=configuration.resolve(document['workflows']['dispatchBindings'],
        {'${SUBSCRIPTION_UID}':subscription,'${ASSIGNMENT_UID}':assignment})
    workflows=document['workflows']
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
    operator.shared.private_file(state/'workflow/reader/delivery.json',json.dumps(dict(profileDigest=staged['profileDigest'],
        workflowIds=[item['id'] for item in workflows['workflows']])))
    operator.shared.private_file(state/'workflow/worker/listener.json',json.dumps(dict(name=settings['subscription'],uid=subscription)))
    qualify_reader(operator,config,state)
    return config


def deploy(settings,state,compose,build):
    state.mkdir(mode=0o700,parents=True,exist_ok=True)
    operator=modules(state,compose)
    model_custody(operator,state)
    if build:
        run([sys.executable,'tools/quickstart/check.py','--build','--build-only','--image',os.environ['TOOLGATE_QUICKSTART_IMAGE']])
        run(['docker','build','-t',os.environ['TOOLGATE_RUNTIME_IMAGE'],'apps/langgraph-runtime'])
    prepare_packages(operator,state,compose)
    bootstrap_profile(settings,state)
    if os.environ.get('TOOLGATE_MONITORING','false').lower()=='true':
        run(compose+['up','-d','insights-collector','prometheus','tempo','grafana'])
    run(compose+['up','-d','--build','--wait','--wait-timeout','240','quickstart','linux-device','workflow-model'])
    if (state/'agents/linux.json').exists():
        operator.verify_deployment('linux')
    else:
        operator.provision(argparse.Namespace(bootstrap_local=True,approve_linux_device=True,target='linux',device_id=None))
    config=profile(operator,settings,state)
    exchange=HERE/'data'/settings['id']
    for folder in settings['fileAgent'].values():
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
    run(compose+['up','-d','--build','--force-recreate','identity'])
    deadline=time.monotonic()+90
    while (not (state/'workflow/reader/result.token').is_file()
            or not (state/'workflow/identity-ready').is_file()
            or int((state/'workflow/identity-ready').read_text())<started):
        if time.monotonic()>deadline:
            raise ValueError('Identity service did not become ready; inspect its metadata logs')
        time.sleep(1)
    run(compose+['up','-d','--no-deps','--force-recreate','workflow-worker','workflow-listener','folder-agent'])
    print('Configuration: '+settings['id'])
    print('Console: http://127.0.0.1:'+os.environ['TOOLGATE_HTTP_PORT']+'/console/')
    print('Drop .txt files in: '+str(exchange/settings['fileAgent']['inbox']))
    print('Outputs: '+str(exchange/settings['fileAgent']['output']))


def ensure_start_images():
    for variable,component in [('TOOLGATE_QUICKSTART_IMAGE','quickstart'),('TOOLGATE_RUNTIME_IMAGE','runtime')]:
        reference=os.environ[variable]
        if subprocess.run(['docker','image','inspect',reference],capture_output=True).returncode==0:
            continue
        if '@sha256:' in reference:
            run(['docker','pull',reference])
        elif component=='quickstart':
            run([sys.executable,'tools/quickstart/check.py','--build','--build-only','--image',reference])
        else:
            run(['docker','build','-t',reference,'apps/langgraph-runtime'])


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=['start','deploy','stop','destroy','status','logs'])
    parser.add_argument('--configuration')
    parser.add_argument('--build',action='store_true',help='Build current source images before provisioning')
    args=parser.parse_args()
    try:
        if args.command=='destroy':
            lifecycle.destroy(HERE,args.configuration)
            return
        if args.command=='start' and not args.configuration:
            args.configuration=lifecycle.choose(HERE,default_configuration())
        settings,state,compose=environment(args.configuration)
        if args.command in ('start','deploy'):
            if args.command=='start' and not args.build:
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
