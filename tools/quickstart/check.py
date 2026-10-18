# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Real one-image SQLite, Gateway, ASK, direct TLS enrollment and offline recovery gate."""
import argparse
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import secrets
import shutil
import ssl
import statistics
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

ROOT=Path(__file__).resolve().parents[2]
WINDOWS=os.name=='nt'
sys.path.insert(0,str(ROOT/'tools/control'))
from check import request, database


def run(args, **kwargs):
    cwd=kwargs.pop('cwd',ROOT)
    if WINDOWS:
        if args[0]=='npm':args=['npm.cmd',*args[1:]]
        elif args[0]=='./gradlew':args=[str(Path(cwd)/'gradlew.bat'),*args[1:]]
    return subprocess.run(args,cwd=cwd,check=True,**kwargs)


def ready(container, port):
    deadline=time.monotonic()+100
    while time.monotonic()<deadline:
        try:
            if request(f'http://127.0.0.1:{port}/health/ready')[0]==200:return
        except Exception:pass
        state=json.loads(run(['docker','inspect',container],capture_output=True,text=True).stdout)[0]['State']
        if not state['Running']:
            # Production logs are redacted by design; print them only on a real startup failure.
            logs=run(['docker','logs',container],capture_output=True,text=True)
            output=ROOT/'build/quickstart';output.mkdir(parents=True,exist_ok=True);(output/'startup-failure.log').write_text(logs.stdout+logs.stderr,encoding='utf-8')
            print('Startup diagnostics: build/quickstart/startup-failure.log')
            raise RuntimeError('Quickstart exited before readiness')
        time.sleep(.25)
    logs=run(['docker','logs',container],capture_output=True,text=True)
    output=ROOT/'build/quickstart';output.mkdir(parents=True,exist_ok=True);(output/'startup-failure.log').write_text(logs.stdout+logs.stderr,encoding='utf-8')
    raise RuntimeError('Quickstart readiness timeout; see build/quickstart/startup-failure.log')


def restart_ready(container):
    run(['docker','restart','-t','35',container],capture_output=True)
    # Docker may reassign automatically published host ports when restarting.
    ports=json.loads(run(['docker','inspect',container],capture_output=True,text=True).stdout)[0]['NetworkSettings']['Ports']
    ready(container,ports['8080/tcp'][0]['HostPort'])
    return ports


def tls(url, context, token=None, body=None, method='GET'):
    headers={'Content-Type':'application/json','Idempotency-Key':secrets.token_hex(16)}
    if token:headers['Authorization']='Bearer '+token
    req=urllib.request.Request(url,json.dumps(body).encode() if body is not None else None,headers,method=method)
    try:
        with urllib.request.urlopen(req,context=context,timeout=15) as response:return response.status,json.loads(response.read())
    except urllib.error.HTTPError as response:return response.code,json.loads(response.read())


TOOL_HOST='tool-host-local'
REMOTE_TOOL_HOST='tool-host-remote'


def toolgate_cli():
    if os.environ.get('TOOLGATE_CLI'):
        return Path(os.environ['TOOLGATE_CLI']).resolve(strict=True)
    if shutil.which('cargo'):
        run(['cargo','build','-q','--locked','-p','toolgate-cli','--bin','toolgate'])
        target=Path(os.environ.get('CARGO_TARGET_DIR',ROOT/'target'))
        if not target.is_absolute():target=ROOT/target
        return target.resolve()/'debug'/('toolgate.exe' if WINDOWS else 'toolgate')
    if not WINDOWS:raise RuntimeError('Cargo is required to build the Quickstart starter tool')
    # Debug Windows hosts already require Linux Docker and use this pinned MinGW toolchain.
    image='olo-toolgate-client-debug-tools:rust-1.94.1'
    target='x86_64-pc-windows-gnu'
    registry=ROOT/'.dev/cargo-registry';registry.mkdir(parents=True,exist_ok=True)
    run(['docker','build','-f',str(ROOT/'debug/windows-client.Dockerfile'),'-t',image,str(ROOT)])
    run(['docker','run','--rm','-v',f'{ROOT.as_posix()}:/work',
         '-v',f'{registry.as_posix()}:/usr/local/cargo/registry','-w','/work',
         '-e','CARGO_TARGET_DIR=/work/target/client-release',image,
         'cargo','build','-q','--release','--locked','-p','toolgate-cli','--bin','toolgate','--target',target])
    return ROOT/'target/client-release'/target/'release/toolgate.exe'


def starter_package():
    """The Java starter as a tool author builds it: `toolgate init`, then `toolgate pack`."""
    run(['./gradlew','-q','--configure-on-demand','publishJavaSdkLocally'])
    toolgate=toolgate_cli()
    build=(ROOT/'build').resolve();build.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='quickstart-starter-',dir=build) as temporary:
        out=Path(temporary).resolve()
        if out.parent!=build or not out.name.startswith('quickstart-starter-'):
            raise ValueError('Starter test directory must be owned by this workspace')
        run([str(toolgate),'init','orders','--dir',str(out),'--id','acme.orders','--publisher','Acme','--sdk-repo',str(ROOT/'build/sdk-repo')])
        run([str(toolgate),'pack'],cwd=out)
        return (out/'build/toolgate/orders-0.1.0.tgpkg').read_bytes()


def smoke(image, browser, database_options=None, gateway_image=None, hello_world_only=False):
    """Fresh group-only installation through real JWT, TLS, device and reviewed HTTP APIs."""
    package=None if hello_world_only else starter_package()
    name='toolgate-enterprise-test-'+secrets.token_hex(6)
    volume=name+'-data'; containers=[]; report={'image':image,'storage':'PostgreSQL' if database_options else 'SQLite','gates':[]}
    output=ROOT/'build/quickstart';output.mkdir(parents=True,exist_ok=True)
    (output/('hello-world-smoke.json' if hello_world_only else 'smoke-postgresql.json' if database_options else 'smoke.json')).unlink(missing_ok=True)
    if browser:(output/'browser-smoke.json').unlink(missing_ok=True)
    def mark(gate):
        report['gates'].append(gate);print('PASS '+gate,flush=True)
    def exec_text(*args):
        return run(['docker','exec',container,*args],capture_output=True,text=True).stdout.strip()
    try:
        run(['docker','volume','create',volume],capture_output=True)
        container=run(['docker','run','-d',*(database_options or []),'--name',name,'--read-only','--cap-drop=ALL',
            '--security-opt=no-new-privileges','--memory=1536m','--cpus=2',
            '--tmpfs','/tmp:rw,noexec,nosuid,size=128m,uid=65532,gid=65532',
            '-e','TOOLGATE_ALLOW_LOOPBACK_MCP_HTTP=true',
            '-p','127.0.0.1::8080','-p','127.0.0.1::8443',*(['-p','127.0.0.1::8084','-p','127.0.0.1::9094'] if gateway_image else []),'-v',volume+':/data',image],
            capture_output=True,text=True).stdout.strip()
        containers.append(container)
        info=json.loads(run(['docker','inspect',container],capture_output=True,text=True).stdout)[0]
        port=info['NetworkSettings']['Ports']['8080/tcp'][0]['HostPort']
        tls_port=info['NetworkSettings']['Ports']['8443/tcp'][0]['HostPort']
        started=time.monotonic();ready(container,port)
        report['firstBootSeconds']=round(time.monotonic()-started,2)
        origin=f'http://127.0.0.1:{port}'
        mcp_origin=origin
        if gateway_image:
            delegated_token=secrets.token_urlsafe(48)
            delegated_digest=hashlib.sha256(delegated_token.encode()).hexdigest()
            # Exact production Gateway, online authority on an explicit fixture-only loopback hop.
            exec_text('/opt/quickstart-python/bin/python','-c',
                "import sys;sys.path.insert(0,'/opt/quickstart');import supervisor as s;import json;"
                "c=json.loads((s.DATA/'run/gateway.json').read_text());c['listen']='0.0.0.0:8084';c['trustedTlsProxy']=True;"
                "c['managementListen']='0.0.0.0:9094';s.atomic(s.DATA/'run/external-gateway.json',json.dumps(c))")
            exec_text('/opt/quickstart-python/bin/python','-c',
                "import sys;sys.path.insert(0,'/opt/quickstart');import supervisor as s;import json,time;"
                "db=s.Database();identities=[json.loads(r[0]) if isinstance(r[0],str) else r[0] for r in db.execute(\"SELECT document FROM control_records WHERE kind='IDENTITY_BINDING'\").fetchall()];db.connection.close();"
                "i=next(i for i in identities if i['subject']=='admin');values=json.loads((s.DATA/'run/credentials.json').read_text());"
                "c={**values[0]['context'],'mode':'DELEGATED','workloadBindingId':'workload-delegated','userId':i['userId'],'sessionEpoch':i['sessionEpoch'],'credentialSha256':"+repr(delegated_digest)+"};"
                "values.append(dict(tokenSha256="+repr(delegated_digest)+",context=c,expiresAtUnixMs=int(time.time()*1000)+3600000));"
                "s.atomic(s.DATA/'run/external-credentials.json',json.dumps(values))")
            external=run(['docker','run','-d','--read-only','--cap-drop=ALL','--security-opt=no-new-privileges',
                '--memory=256m','--cpus=1','--network','container:'+container,'-v',volume+':/data:ro',
                '-e','TOOLGATE_GATEWAY_CONFIG=/data/run/external-gateway.json',
                '-e','TOOLGATE_GATEWAY_CREDENTIALS=/data/run/external-credentials.json',gateway_image],capture_output=True,text=True).stdout.strip()
            containers.append(external)
            gateway_info=json.loads(run(['docker','inspect',external],capture_output=True,text=True).stdout)[0]
            assert gateway_info['Config']['User']=='65532:65532' and gateway_info['HostConfig']['ReadonlyRootfs']
            mcp_origin='http://127.0.0.1:'+info['NetworkSettings']['Ports']['8084/tcp'][0]['HostPort']
            gateway_management='http://127.0.0.1:'+info['NetworkSettings']['Ports']['9094/tcp'][0]['HostPort']
            deadline=time.monotonic()+40
            while True:
                try:
                    if request(gateway_management+'/v1/health/ready')[0]==200:break
                except (urllib.error.URLError,TimeoutError,ConnectionError):pass
                assert time.monotonic()<deadline,'Production Gateway readiness failed'
                time.sleep(.25)
            assert request(gateway_management+'/v1/metrics')[0]==200
            assert request(mcp_origin+'/v1/health/live',exec_text('cat','/data/run/runtime-token'))[0]==404
            report['gatewayImage']=gateway_image
            mark('production Gateway image/nonroot/read-only/separate management/current Core readiness')
        def api(path,token=None,body=None,method='GET',key=None,revision=None):
            headers={}
            if body is not None or method in ('POST','PUT','DELETE'):headers['Idempotency-Key']=key or secrets.token_hex(16)
            if revision is not None:headers['If-Match']='"'+str(revision)+'"'
            status,raw,_=request(origin+path,token,body,method,headers)
            value=json.loads(raw) if raw else None
            return status,value
        def ok(result,expected=200):
            assert result[0]==expected,(result[0],result[1]);return result[1]
        assert api('/api/control/v1/users')[0]==401
        assert request(origin+'/api/quickstart/v1/status',headers={'Host':'hostile.invalid'})[0]==401
        tokens={};passwords={}
        for user in ('admin','reviewer-1','reviewer-2'):
            filename='bootstrap-password'+('' if user=='admin' else '-'+user)
            initial=exec_text('cat','/data/'+filename)
            passwords[user]=secrets.token_urlsafe(32)
            login=api('/api/quickstart/v1/login',body={'username':user,'password':initial,
                'newPassword':passwords[user]},method='POST')
            assert login[0]==200,('Login failed for '+user,login[0],login[1])
            tokens[user]=login[1]['accessToken']
        admin=tokens['admin'];human=tokens['reviewer-1']
        snapshot=ok(api('/api/control/v1/config/export',admin))
        assert all(g['id'].startswith('standard-') for g in snapshot['grants']) and snapshot['delegations']==[]
        assert all(not tool['enabled'] for tool in snapshot['tools'])
        for key,default,suffix in [('agentGroups','default-agents','AgentGroup'),('toolGroups','default-tools','ToolGroup'),('deviceGroups','default-devices','DeviceGroup')]:
            assert {x['id'] for x in snapshot[key]}=={default,*[tier+suffix for tier in ('ReadOnly','ReadAndWrite','Admin')]}
            member={'agentGroups':'agentIds','toolGroups':'toolIds','deviceGroups':'deviceIds'}[key]
            assert all(not row[member] for row in snapshot[key] if row['id']!=default)
        mark('fresh reviewed installation/independent identities/default groups/zero runtime rights')
        # Quickstart has no native client: its execution identities are its two Tool Hosts, approved like any device.
        # The fresh profile then deploys Hello World to tool-host-local and enables server_greet; nobody may run it yet.
        owner=admin
        def approve(device_id):
            deadline=time.monotonic()+45
            while True:
                pending=[e for e in ok(api('/api/control/v1/endpoint/enrollments',admin))['items'] if e['deviceId']==device_id]
                if pending:break
                assert time.monotonic()<deadline,device_id+' did not begin enrollment'
                time.sleep(.5)
            review=ok(api('/api/control/v1/endpoint/enrollments/review?code='+pending[0]['userCode'],admin))
            ok(api('/api/control/v1/endpoint/enrollments/decision',admin,
                {'userCode':review['userCode'],'keyFingerprint':review['keyFingerprint'],'choice':'APPROVE'},'POST'))
        approve(TOOL_HOST);approve(REMOTE_TOOL_HOST)
        device=TOOL_HOST;hello='ololabs.hello-world/server_greet'
        hello_tools={hello,'ololabs.hello-world-python/server_greet'}
        deadline=time.monotonic()+180
        while True:
            status,provisioned=api('/api/control/v1/packages/provisioning/fresh',owner)
            if status==200 and provisioned['state']=='READY':break
            assert time.monotonic()<deadline,('The fresh profile did not become READY',status,provisioned)
            time.sleep(1)
        for tool_name in hello_tools:
            assert ok(api('/api/control/v1/tools/'+tool_name,admin))['enabled'] is True
        assert device in ok(api('/api/control/v1/device-groups/default-devices',admin))['deviceIds']
        target=dict(bindingId='binding-default',deviceId=device)
        def catalog(token=None):
            return {t['toolId'] for t in ok(api('/api/control/v1/access/human/catalog',token or human,target,'POST'))['tools']}
        assert catalog()==set()
        mark('Tool Host enrollment/fresh profile installs and activates Hello World/no implicit discovery')
        # The bootstrap account must retain Super Admin authority. Keep it as the installation owner;
        # the two other local accounts become ordinary administrators for maker/checker and HUMAN tests.
        management=ok(api('/api/control/v1/roles/installation-management',admin))
        refused=api('/api/control/v1/roles/'+management['id'],admin,{**management,'portalRole':'ADMINISTRATOR'},
            'PUT',revision=management['revision'])
        assert refused[0]==409,refused
        restricted={**management,'id':'smoke-restricted-management','name':'Smoke restricted administrator',
                    'revision':1,'portalRole':'ADMINISTRATOR'}
        ok(api('/api/control/v1/roles',admin,restricted,'POST'),201)
        ok(api('/api/control/v1/teams',admin,dict(id='smoke-makers',name='Smoke makers',
            enabled=True,revision=1,userIds=['reviewer-1','reviewer-2'],roleIds=[restricted['id']]),'POST'),201)
        owners=ok(api('/api/control/v1/teams/installation-administrators',admin))
        ok(api('/api/control/v1/teams/installation-administrators',owner,
            {**owners,'userIds':['admin']},'PUT',revision=owners['revision']))
        def transition(change,action,user):
            return ok(api('/api/control/v1/configuration-changes/'+change['id']+'/transition',tokens[user],
                {'action':action,'expectedRevision':change['revision']},'POST'))
        def reviewed(path,document,method='POST',revision=None):
            maker=tokens['reviewer-1'];key=secrets.token_hex(16)
            change=ok(api(path,maker,document,method,key,revision),202)
            assert ok(api(path,maker,document,method,key,revision),202)['id']==change['id']
            change=transition(change,'SUBMIT','reviewer-1')
            denied=api('/api/control/v1/configuration-changes/'+change['id']+'/transition',maker,
                {'action':'APPROVE','expectedRevision':change['revision']},'POST')
            assert denied[0]==403,denied
            # A Super Admin's review approves at once; ordinary reviews count toward requiredReviews.
            for reviewer in ('admin','reviewer-2'):
                if change['state']!='PENDING':break
                change=transition(change,'APPROVE',reviewer)
            assert change['state']=='APPROVED',change
            return transition(change,'APPLY','reviewer-1')
        # The public UI adapter only accepts its local password identities; use Core's
        # real TLS interface for an externally verified identity with no directory claim.
        with tempfile.TemporaryDirectory(prefix='toolgate-public-ca-') as certs:
            ca=Path(certs)/'ca.crt'
            run(['docker','cp',container+':/data/keys/device-ca.crt',str(ca)],capture_output=True)
            trust=ssl.create_default_context(cafile=str(ca))
            script="import sys;sys.path.insert(0,'/opt/quickstart');import supervisor;print(supervisor.jwt(['toolgate-client'],'verified-new-person',directory_bound=False))"
            newcomer=exec_text('/opt/quickstart-python/bin/python','-c',script)
            users_before=ok(api('/api/control/v1/users',admin))['items']
            invalid=newcomer[:-8]+'AAAAAAAA'
            url=f'https://127.0.0.1:{tls_port}/api/control/v1/access/human/catalog'
            target=dict(bindingId='binding-default',deviceId=device)
            assert tls(url,trust,invalid,target,'POST')[0]==401
            assert len(ok(api('/api/control/v1/users',admin))['items'])==len(users_before)
            assert tls(url,trust,newcomer,target,'POST')[0]==403
            after=ok(api('/api/control/v1/users',admin))['items']
            added=[u for u in after if u['id'] not in {v['id'] for v in users_before}]
            assert len(added)==1 and added[0]['enabled'] is False,added
            assert added[0]['id'] in ok(api('/api/control/v1/teams/team-default',admin))['userIds']
            assert tls(url,trust,newcomer,target,'POST')[0]==403
            assert len(ok(api('/api/control/v1/users',admin))['items'])==len(after)
            reviewed('/api/control/v1/users/'+added[0]['id'],{**added[0],'enabled':True},'PUT',added[0]['revision'])
            assert ok(api('/api/control/v1/users/'+added[0]['id'],admin))['enabled'] is True
        mark('verified JWT creates one disabled User/default Team/invalid JWT creates none/reviewed activation')
        # Complete HUMAN grant; management Role alone never supplies this runtime capability.
        conditions=dict(notBeforeUnixMs=0,expiresAtUnixMs=0,networkCidrs=[],devicePosture=[],regions=[],
                        hoursUtc=[],requireOnline=True,highRisk=False)
        scope=dict(toolGroups=dict(ids=['default-tools'],all=False),
                   deviceGroups=dict(ids=['default-devices'],all=False),actions=['invoke'],allActions=False,
                   resources=[dict(kind='CUSTOM',locator='tool://'+name,match='EXACT') for name in sorted(hello_tools)],conditions=conditions)
        grant=dict(id='human-hello',name='Reviewed Hello World grant',enabled=True,revision=1,
                   sourceType='TEAM',sourceId='smoke-makers',purpose='HUMAN',scope=scope)
        reviewed('/api/control/v1/grants',grant)
        assert catalog()==hello_tools,catalog()
        mark('HTTP maker/checker/idempotent drafts/self-review rejection/complete grant discovery')
        timings=[]
        for index in range(108):
            start=time.perf_counter_ns();catalog();elapsed=(time.perf_counter_ns()-start)/1e6
            if index>=8:timings.append(elapsed)
        ordered=sorted(timings)
        report['discoveryPerformance']={'samples':len(timings),'warmup':8,'p50Ms':round(statistics.median(timings),3),
            'p95Ms':round(ordered[94],3),'p99Ms':round(ordered[98],3),'cpuLimit':2,'memoryLimitMiB':1536,
            'toolCount':len(ok(api('/api/control/v1/tools',admin))['items']),'transport':'loopback HTTP via identity adapter',
            'storage':report['storage'],'catalogGrants':1,'interpretation':'Observed small-fixture latency; no enterprise capacity claim'}
        mark('measured current-authority HTTP discovery latency')
        agent=ok(api('/api/control/v1/agents/agent-local',admin))
        reviewed('/api/control/v1/agents/agent-local',{**agent,'enabled':True},'PUT',agent['revision'])
        service_token=exec_text('cat','/data/run/runtime-token')
        reviewed('/api/control/v1/workload-bindings',dict(id='workload-local',name='Reviewed service workload',
            enabled=True,revision=1,agentId='agent-local',mode='SERVICE',issuer='gateway',subject='local-workload',
            audience='gateway',credentialSha256=hashlib.sha256(service_token.encode()).hexdigest(),
            credentialEpoch=1,expiresAtUnixMs=int(time.time()*1000)+3600000))
        for purpose in ('CAPABILITY','SERVICE'):
            reviewed('/api/control/v1/grants',dict(id='service-'+purpose.lower(),name='Reviewed service '+purpose,
                enabled=True,revision=1,sourceType='AGENT_GROUP',sourceId='default-agents',purpose=purpose,scope=scope))
        service_token=exec_text('cat','/data/run/runtime-token')
        def mcp(method,params=None,key=None,token=None):
            body={'jsonrpc':'2.0','id':key or secrets.token_hex(16),'method':method,'params':{
                **(params or {}),'_meta':{'io.modelcontextprotocol/protocolVersion':'2026-07-28','io.modelcontextprotocol/clientCapabilities':{'tools':{}}}}}
            headers={'Accept':'application/json, text/event-stream','MCP-Protocol-Version':'2026-07-28',
                'MCP-Method':method,'X-Request-ID':body['id'],'Idempotency-Key':body['id']}
            if method=='tools/call':headers['MCP-Name']=params['name']
            status,raw,_=request(mcp_origin+'/mcp',token or service_token,body,'POST',headers)
            return status,json.loads(raw)
        listed_hello={t['name']:t for t in ok(mcp('tools/list'))['result']['tools']}
        assert set(listed_hello)==hello_tools
        assert len({json.dumps(tool['inputSchema'],sort_keys=True) for tool in listed_hello.values()})==1
        definitions=[ok(api('/api/control/v1/tools/'+name,admin))['definition'] for name in sorted(hello_tools)]
        for schema in ('inputSchema','outputSchema'):
            assert len({json.dumps(tool[schema],sort_keys=True) for tool in definitions})==1,definitions
        hello_results={}
        for tool_name in sorted(hello_tools):
            for arguments,expected in (({},'Hello World'),({'text':'Ada'},'Hello Ada')):
                service_key=secrets.token_hex(16)
                service_result=ok(mcp('tools/call',{'name':tool_name,'arguments':arguments},service_key))
                assert service_result['result']['isError'] is False,service_result
                assert service_result['result']['structuredContent']=={'message':expected},service_result
                assert ok(mcp('tools/call',{'name':tool_name,'arguments':arguments},service_key))['result']==service_result['result']
                hello_results.setdefault(tool_name,[]).append(service_result['result']['structuredContent'])
        assert len({json.dumps(results,sort_keys=True) for results in hello_results.values()})==1
        report['helloWorldParity']={'tools':sorted(hello_tools),'arguments':[{}, {'text':'Ada'}],
                                   'outputs':hello_results[hello],'transport':'Gateway MCP -> Control permit -> Tool Host'}
        mark('Java/Python Hello World parity: same MCP calls/defaults/output/exact retry, changing only tool name')
        service_grant=ok(api('/api/control/v1/grants/service-service',admin))
        reviewed('/api/control/v1/grants/service-service',{**service_grant,'enabled':False},'PUT',service_grant['revision'])
        assert ok(mcp('tools/list'))['result']['tools']==[]
        for tool_name in hello_tools:
            assert mcp('tools/call',{'name':tool_name,'arguments':{'text':'Ada'}},service_key)[0]==403
        mark('real authenticated Gateway SERVICE/group capability/Tool Host effect/exact retry/live revocation')
        if hello_world_only:
            report['scope']='Java/Python Hello World through the real Gateway, Control and Tool Host'
            (output/'hello-world-smoke.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
            print('Real Java/Python Hello World parity gate passed')
            return
        # A Java server tool authored with the SDK, end to end: Control verifies and deploys the starter package, and a
        # Gateway call is placed on the host, permitted by Control and run from that exact package.
        # Package upload and deployment are the installation owner's (Super Admin).
        status,raw,_=request(origin+'/api/control/v1/packages/uploads',owner,package,'POST',
            {'Content-Type':'application/vnd.ololabs.toolgate.package+zip','Idempotency-Key':secrets.token_hex(16)})
        assert status==201,(status,raw)
        uploaded=json.loads(raw);digest=uploaded['packageDigest']
        assert ok(api('/api/control/v1/packages',admin))['items'][0]['packageDigest']==digest
        def deploy(host_id):
            deadline=time.monotonic()+60
            while True:  # A host can be named once it has registered as a Tool Host.
                status,value=api('/api/control/v1/packages/deployments',owner,
                    {'packageDigest':digest,'globals':{'Greeting':'Hi','ItemsApiHost':'items.invalid'},'hostIds':[host_id]},'POST')
                if status==200:return value
                assert status==400 and time.monotonic()<deadline,(host_id,status,value)
                time.sleep(1)
        def ran_on(host_id):
            count,after=0,None
            while True:
                page=ok(api('/api/control/v1/access/invocations?limit=100'+('&after='+after if after else ''),admin))
                count+=sum(1 for i in page['items'] if i['evaluation']['context'].get('deviceId')==host_id and i['evaluation'].get('toolId')=='acme.orders/greet' and i['state']=='SUCCEEDED')
                after=page.get('nextCursor')
                if not after:return count
        deployment=deploy(TOOL_HOST)
        assert deployment['hostIds']==[TOOL_HOST],deployment
        assert set(deployment['toolIds'])=={'acme.orders/greet','acme.orders/items.get'},deployment
        greet=ok(api('/api/control/v1/tools/acme.orders/greet',admin))
        assert greet['enabled'] is False
        reviewed('/api/control/v1/tools/acme.orders/greet',{**greet,'enabled':True},'PUT',greet['revision'])
        assert 'acme.orders/greet' in ok(api('/api/control/v1/tool-groups/default-tools',admin))['toolIds']
        binding=ok(api('/api/control/v1/bindings/binding-default',admin))
        reviewed('/api/control/v1/bindings/binding-default',{**binding,'actions':sorted({*binding['actions'],'invoke'}),
            'allowedPackageDigests':sorted({*binding['allowedPackageDigests'],digest.removeprefix('sha256:')})},'PUT',binding['revision'])
        orders=dict(scope,actions=['invoke'],resources=[dict(kind='CUSTOM',locator='tool://acme.orders/greet',match='EXACT')])
        for purpose in ('CAPABILITY','SERVICE'):
            reviewed('/api/control/v1/grants',dict(id='orders-'+purpose.lower(),name='Reviewed starter '+purpose,
                enabled=True,revision=1,sourceType='AGENT_GROUP',sourceId='default-agents',purpose=purpose,scope=orders))
        deadline=time.monotonic()+60
        while True:
            listed={t['name'] for t in ok(mcp('tools/list'))['result']['tools']}
            if 'acme.orders/greet' in listed:break
            assert time.monotonic()<deadline,('The Tool Host never reported the starter tools',listed,exec_text('ls','-R','/data/tool-host'))
            time.sleep(1)
        assert ok(api('/api/control/v1/endpoint/devices/'+TOOL_HOST,admin))['lastSeenUnixMs']>0
        hosted=ok(mcp('tools/call',{'name':'acme.orders/greet','arguments':{'name':'Ada'}}));assert 'result' in hosted,hosted
        assert hosted['result']['structuredContent']=={'text':'Hi, Ada'} and hosted['result']['isError'] is False,hosted
        def placed(local,remote):  # The host's report settles the invocation just after the call answers.
            deadline=time.monotonic()+15
            while (ran_on(TOOL_HOST),ran_on(REMOTE_TOOL_HOST))!=(local,remote):
                assert time.monotonic()<deadline,('Invocations did not run where deployed',ran_on(TOOL_HOST),ran_on(REMOTE_TOOL_HOST))
                time.sleep(.5)
        placed(1,0)
        # Switch the device: the same version, deployed to the host the Gateway cannot reach. It fetches the call from
        # Control and answers through it.
        assert deploy(REMOTE_TOOL_HOST)['hostIds']==[REMOTE_TOOL_HOST]
        deadline=time.monotonic()+120
        while True:  # The tool is unavailable until the remote host has fetched the deployment and checked in.
            status,moved=mcp('tools/call',{'name':'acme.orders/greet','arguments':{'name':'Grace'}})
            if status==200 and 'result' in moved:break
            assert status in (200,403),(status,moved)
            assert time.monotonic()<deadline,('The remote Tool Host never took the call',moved,exec_text('ls','-R','/data/tool-host-remote'))
            time.sleep(1)
        assert moved['result']['structuredContent']=={'text':'Hi, Grace'},moved
        placed(1,1)
        for grant_id in ('orders-service','orders-capability'):
            starter=ok(api('/api/control/v1/grants/'+grant_id,admin))
            reviewed('/api/control/v1/grants/'+grant_id,{**starter,'enabled':False},'PUT',starter['revision'])
        assert ok(mcp('tools/list'))['result']['tools']==[]
        assert mcp('tools/call',{'name':'acme.orders/greet','arguments':{'name':'Ada'}})[0]==403
        mark('verified package upload/deployment/Gateway permit/Java tool run from the exact package/switch to a pull-mode host/live revocation')
        if gateway_image:
            identity=next(i for i in ok(api('/api/control/v1/identity-bindings',admin))['items'] if i['subject']=='reviewer-1')
            reviewed('/api/control/v1/workload-bindings',dict(id='workload-delegated',name='Verified human delegation',
                enabled=True,revision=1,agentId='agent-local',mode='DELEGATED',issuer='gateway',subject='local-workload',
                audience='gateway',credentialSha256=delegated_digest,credentialEpoch=1,
                expiresAtUnixMs=int(time.time()*1000)+3600000,delegatedUserId=identity['userId'],delegatedSessionEpoch=identity['sessionEpoch']))
            assert ok(mcp('tools/list',token=delegated_token))['result']['tools']==[]
            reviewed('/api/control/v1/delegations',dict(id='human-delegation',name='Same Team and Agent Group',
                enabled=True,revision=1,teamId='smoke-makers',agentGroupId='default-agents',scope=scope))
            assert {t['name'] for t in ok(mcp('tools/list',token=delegated_token))['result']['tools']}==hello_tools
            result=ok(mcp('tools/call',{'name':hello,'arguments':{'text':'Grace'}},token=delegated_token))
            assert result['result']['structuredContent']=={'message':'Hello Grace'},result
            delegation=ok(api('/api/control/v1/delegations/human-delegation',admin))
            reviewed('/api/control/v1/delegations/human-delegation',{**delegation,'enabled':False},'PUT',delegation['revision'])
            assert ok(mcp('tools/list',token=delegated_token))['result']['tools']==[]
            assert mcp('tools/call',{'name':hello,'arguments':{'text':'Grace'}},token=delegated_token)[0]==403
            mark('real DELEGATED identity/same Team and Agent Group/Tool Host effect/current delegation revocation')
        def invoke(arguments,key=None,tool_name=hello):
            return api('/api/control/v1/access/human/invocations',human,
                       dict(target,request=dict(toolId=tool_name,action='invoke',arguments=arguments)),'POST',key)
        args={'text':'World'};human_invocations={}
        for tool_name in sorted(hello_tools):
            key=secrets.token_hex(16)
            outcome=ok(invoke(args,key,tool_name));invocation=outcome['invocation']
            assert invocation['evaluation']['context']['mode']=='HUMAN'
            assert invocation['evaluation']['context']['userId']=='reviewer-1'
            deadline=time.monotonic()+45
            while True:
                result=api('/api/control/v1/access/human/invocations/'+invocation['id']+'/result',human)
                if result[0]==200 and result[1].get('result') is not None:break
                assert time.monotonic()<deadline,('Effect did not complete',tool_name,result)
                time.sleep(.5)
            assert result[1]['result']['output']=={'message':'Hello World'},result
            assert ok(invoke(args,key,tool_name))['invocation']['id']==invocation['id']
            assert invoke({'text':'someone else'},key,tool_name)[0]==409
            saved=ok(api('/api/control/v1/access/invocations/'+invocation['id'],admin))
            assert saved['state']=='SUCCEEDED',saved
            human_invocations[tool_name]=(key,invocation,saved)
        key,invocation,saved=human_invocations[hello]
        mark('Java/Python HUMAN invocation/Tool Host effect/signed permit/durable result/exact retry conflict')
        # ASK is discoverable but supplies no execution permit. Use the ordinary maker's team
        # as approvers: reviewer-1 was deliberately removed from installation-administrators above.
        policy=dict(id='hello-review',name='Independent greeting review',enabled=True,revision=1,
                    scope=scope,decision='ASK',teams=dict(ids=[],all=True),agentGroups=dict(ids=[],all=True),approverTeams=dict(ids=['smoke-makers'],all=False))
        reviewed('/api/control/v1/policies',policy)
        ask_key=secrets.token_hex(16);ask=ok(invoke(args,ask_key),202)['invocation']
        assert ask['state']=='PENDING_APPROVAL'
        approvals=ok(api('/api/control/v1/approvals',tokens['reviewer-2']))['items']
        approval=next(a for a in approvals if a['invocationId']==ask['id'])
        decision=dict(obligationId='hello-review',expectedRevision=approval['revision'],decision='APPROVE')
        assert api('/api/control/v1/approvals/'+approval['id']+'/decision',human,decision,'POST')[0]==403
        ok(api('/api/control/v1/approvals/'+approval['id']+'/decision',tokens['reviewer-2'],decision,'POST'))
        ok(invoke(args,ask_key))
        deadline=time.monotonic()+45
        while True:
            result=api('/api/control/v1/access/human/invocations/'+ask['id']+'/result',human)
            if result[0]==200 and result[1].get('result') is not None:break
            assert time.monotonic()<deadline,result
            time.sleep(.5)
        mark('ASK discovery/exact operation approval/independent reviewer/approved effect')
        # Revocation is current online authority, even for saved results or an identical retry.
        current=ok(api('/api/control/v1/grants/'+grant['id'],admin))
        reviewed('/api/control/v1/grants/'+grant['id'],{**current,'enabled':False},'PUT',current['revision'])
        assert catalog()==set()
        assert invoke(args,key)[0]==403
        assert api('/api/control/v1/access/human/invocations/'+invocation['id']+'/result',human)[0]==403
        mark('live revocation hides discovery/blocks execution/retries/result retrieval')
        authority=ok(api('/api/control/v1/access/status',admin))
        operational=ok(api('/api/control/v1/access/operations',admin))
        assert operational['permitLifetimeMs']==10000 and operational['clockSkewMs']==0
        evaluation={**saved['evaluation'],'nowUnixMs':int(time.time()*1000)}
        assert ok(api('/api/control/v1/access/simulate',admin,evaluation,'POST'))['decision']=='BLOCK'
        baseline=ok(api('/api/control/v1/config/export',admin))
        sys.path.insert(0,str(ROOT/'tools/enterprise'))
        spec=importlib.util.spec_from_file_location('migration_gate',ROOT/'tools/enterprise/migration.py')
        migration=importlib.util.module_from_spec(spec);spec.loader.exec_module(migration)
        team=next(t for t in baseline['teams'] if t['id']=='installation-administrators')
        proposed,evidence=migration.prepare(dict(tenantId=baseline['tenantId'],source='isolated retained configuration',snapshot=baseline),
            baseline,dict(tenantId=baseline['tenantId'],revision=baseline['revision'],
                          records={'teams':[{**team,'name':'Reviewed installation operators'}]},reasonDigest='b'*64))
        shadow=ok(api('/api/control/v1/access/shadow',admin,dict(snapshot=proposed,evaluation=evaluation,
            observedLegacyDecision='BLOCK',legacyEvidenceDigest=evidence['archiveDigest']),'POST'))
        assert shadow['accessExpansion'] is False and shadow['decision']['decision']=='BLOCK'
        # A restricted administrator cannot modify a Super Admin role. Unchanged owner records
        # in a replacement snapshot are allowed, so request an actual owner-role change for this denial.
        # The installation owner can apply the import directly, with canonical lifecycle checks and audit.
        import_body=dict(snapshot=proposed,mode='REPLACE',dryRun=False)
        privileged_import={**import_body,'snapshot':{**proposed,'roles':[
            {**role,'name':role['name']+' changed'} if role['portalRole']=='SUPER_ADMIN' else role
            for role in proposed['roles']]}}
        assert api('/api/control/v1/config/import',human,privileged_import,'POST',revision=baseline['revision'])[0]==403
        ok(api('/api/control/v1/config/import',owner,import_body,'POST',revision=baseline['revision']))
        assert ok(api('/api/control/v1/teams/'+team['id'],admin))['name']=='Reviewed installation operators'
        evidence.update(shadow=shadow,superAdministratorImportApplied=True,interpretation='Isolated conversion/shadow and audited Super Admin import HTTP proof; production plans require their own captured legacy evidence')
        output=ROOT/'build/enterprise';output.mkdir(parents=True,exist_ok=True)
        (output/('migration-postgresql.json' if database_options else 'migration-sqlite.json')).write_text(json.dumps(evidence,indent=2)+'\n',encoding='utf-8')
        mark('current status/fresh bounds/no-effect simulation/explicit conversion/shadow/scoped rejection/Super Admin import')
        ports=restart_ready(container)
        port=ports['8080/tcp'][0]['HostPort']
        tls_port=ports['8443/tcp'][0]['HostPort']
        origin=f'http://127.0.0.1:{port}'
        mcp_origin=origin if not gateway_image else 'http://127.0.0.1:'+ports['8084/tcp'][0]['HostPort']
        if gateway_image:
            gateway_management='http://127.0.0.1:'+ports['9094/tcp'][0]['HostPort']
            # This fixture shares the parent's network namespace. Docker replaces
            # that namespace on restart; rejoin it before proving authority outage.
            run(['docker','restart','-t','35',external],capture_output=True)
            deadline=time.monotonic()+40
            while True:
                try:
                    if request(gateway_management+'/v1/health/ready')[0]==200:break
                except (urllib.error.URLError,TimeoutError,ConnectionError):pass
                assert time.monotonic()<deadline,'Gateway did not rejoin the restarted fixture'
                time.sleep(.25)
        admin=ok(api('/api/quickstart/v1/login',body={'username':'admin','password':passwords['admin']},method='POST'))['accessToken']
        assert ok(api('/api/control/v1/access/status',admin))['authorizationEpoch']>=authority['authorizationEpoch']
        assert catalog(admin)==set()
        assert ok(api('/api/control/v1/grants/'+grant['id'],admin))['enabled'] is False
        mark('restart preserves identity/key/revocation/monotonic epoch/no installation reseeding')
        if gateway_image and not browser:
            # Freeze only Core: stopping its namespace-owning container also removes
            # Docker's published Gateway ports, which cannot prove fail-closed HTTP.
            signal_core="import os,pathlib,signal;ids=[int(p.parent.name) for p in pathlib.Path('/proc').glob('[0-9]*/comm') if p.read_text().strip()=='java'];assert len(ids)==1;os.kill(ids[0],signal.SIG%s)"
            exec_text('/opt/quickstart-python/bin/python','-c',signal_core%'STOP')
            try:
                deadline=time.monotonic()+15
                while request(gateway_management+'/v1/health/ready')[0]==200:
                    assert time.monotonic()<deadline,'Stale Gateway remained ready without Core'
                    time.sleep(.25)
                assert mcp('tools/list')[0] in (502,503)
            finally:
                exec_text('/opt/quickstart-python/bin/python','-c',signal_core%'CONT')
            deadline=time.monotonic()+20
            while request(gateway_management+'/v1/health/ready')[0]!=200:
                assert time.monotonic()<deadline,'Gateway did not recover current authority'
                time.sleep(.25)
            assert mcp('tools/list')[0]==200
            run(['docker','stop','-t','35',external],capture_output=True)
            state=json.loads(run(['docker','inspect',external],capture_output=True,text=True).stdout)[0]['State']
            assert state['ExitCode']==0,state
            logs=run(['docker','logs',external],capture_output=True,text=True)
            assert service_token not in logs.stdout+logs.stderr
            assert delegated_token not in logs.stdout+logs.stderr
            mark('Core outage fails Gateway closed/recovers without restart/graceful production Gateway shutdown/redacted logs')
        if browser:
            run(['npm','exec','--workspace','@olo-labs/toolgate-admin-ui','--','playwright','test',
                 'tests/e2e/enterprise.spec.ts'],env={**os.environ,'TOOLGATE_ENTERPRISE_ORIGIN':origin,
                 'TOOLGATE_ENTERPRISE_USERNAME':'reviewer-1',
                 'TOOLGATE_ENTERPRISE_PASSWORD':passwords['reviewer-1'],
                 'TOOLGATE_ENTERPRISE_REVIEWER_1_USER':'reviewer-2',
                 'TOOLGATE_ENTERPRISE_REVIEWER_1':passwords['reviewer-2'],
                 'TOOLGATE_ENTERPRISE_REVIEWER_2_USER':'admin',
                 'TOOLGATE_ENTERPRISE_REVIEWER_2':passwords['admin']})
            mark('real accessible enterprise administration browser')
        output=ROOT/'build/quickstart';output.mkdir(parents=True,exist_ok=True)
        (output/('smoke-postgresql.json' if database_options else 'smoke.json')).write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
        if browser:(output/'browser-smoke.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
        print('Fresh enterprise runtime gates passed')
    except Exception:
        output=ROOT/'build/quickstart';output.mkdir(parents=True,exist_ok=True)
        logs=subprocess.run(['docker','logs',container],capture_output=True,text=True)
        (output/'runtime-failure.log').write_text(logs.stdout+logs.stderr,encoding='utf-8')
        for filename in ('installation-review.json','installation-trust.json'):
            subprocess.run(['docker','cp',container+':/data/'+filename,str(output/filename)],capture_output=True)
        raise
    finally:
        for container in reversed(containers):subprocess.run(['docker','rm','-f',container],capture_output=True)
        subprocess.run(['docker','volume','rm',volume],capture_output=True)

def build_images(image='olo-toolgate-quickstart:enterprise-validation',client_assets=None,prebuilt_ui=False):
    assets=[]
    if client_assets:
        path=Path(client_assets).resolve();relative=path.relative_to(ROOT).as_posix()
        if not (path/'manifest.json').is_file():raise ValueError('Validated public client bundle required')
        assets=['--build-arg','CLIENT_ASSETS_DIR='+relative,'--build-arg','CLIENT_DOWNLOADS_DIRECTORY=/opt/toolgate/client-downloads']
    version=(ROOT/'VERSION').read_text().strip()
    revision=run(['git','rev-parse','HEAD'],capture_output=True,text=True).stdout.strip()
    if prebuilt_ui: assets += ['--build-arg','ADMIN_UI_SOURCE=console-prebuilt']
    run(['docker','build','-f','apps/control-plane/Dockerfile','--build-arg','VERSION='+version,'--build-arg','REVISION='+revision,'--build-arg','QUARKUS_PROFILE=quickstart',*assets,'-t','olo-toolgate-control:enterprise-validation','.'])
    run(['docker','build','-f','apps/quickstart/Dockerfile','--build-arg','CONTROL_IMAGE=olo-toolgate-control:enterprise-validation','--build-arg','VERSION='+version,'--build-arg','REVISION='+revision,*assets,'-t',image,'.'])

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--image',default='olo-toolgate-quickstart:enterprise-validation');parser.add_argument('--build',action='store_true');parser.add_argument('--build-only',action='store_true');parser.add_argument('--no-browser',action='store_true');parser.add_argument('--hello-world-only',action='store_true',help='Run the shared Java/Python Hello World Gateway gate and write hello-world-smoke.json');parser.add_argument('--postgresql',action='store_true');parser.add_argument('--gateway-image');parser.add_argument('--client-assets',type=Path);parser.add_argument('--prebuilt-ui',action='store_true');args=parser.parse_args()
    if args.build_only and not args.build:parser.error('--build-only requires --build')
    if args.build:build_images(args.image,args.client_assets,args.prebuilt_ui)
    if args.build_only:return
    if args.postgresql:
        network='toolgate-enterprise-pg-'+secrets.token_hex(6)
        with database() as db,tempfile.TemporaryDirectory(prefix='toolgate-db-custody-') as temp:
            run(['docker','network','create',network],capture_output=True)
            try:
                run(['docker','network','connect','--alias','postgres',network,db['container']],capture_output=True)
                env=Path(temp)/'database.env'
                env.write_text('\n'.join(k+'='+v for k,v in dict(TOOLGATE_QUICKSTART_DATABASE_MODE='postgresql',
                    QUARKUS_DATASOURCE_JDBC_URL='jdbc:postgresql://postgres:5432/control?sslmode=disable',
                    QUARKUS_DATASOURCE_USERNAME='control_app',QUARKUS_DATASOURCE_PASSWORD=db['CONTROL_TEST_PASSWORD'],
                    QUARKUS_FLYWAY_USERNAME='control_migrator',QUARKUS_FLYWAY_PASSWORD=db['CONTROL_TEST_PASSWORD'],
                    TOOLGATE_CONTROL_DEVELOPMENT_MODE='true').items())+'\n',encoding='utf-8');env.chmod(0o600)
                smoke(args.image,not args.no_browser,['--network',network,'--env-file',str(env)],args.gateway_image,args.hello_world_only)
            finally:
                subprocess.run(['docker','network','disconnect',network,db['container']],capture_output=True)
                subprocess.run(['docker','network','rm',network],capture_output=True)
    else:smoke(args.image,not args.no_browser,gateway_image=args.gateway_image,hello_world_only=args.hello_world_only)


if __name__=='__main__':main()
