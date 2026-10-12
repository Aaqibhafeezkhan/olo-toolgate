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
sys.path.insert(0,str(ROOT/'tools/control'))
from check import request, database


def run(args, **kwargs):
    if os.name=='nt' and args[0]=='npm':args=['npm.cmd',*args[1:]]
    return subprocess.run(args,cwd=ROOT,check=True,**kwargs)


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


def smoke(image, browser, database_options=None, gateway_image=None):
    """Fresh group-only installation through real JWT, TLS, device and reviewed HTTP APIs."""
    name='toolgate-enterprise-test-'+secrets.token_hex(6)
    volume=name+'-data'; containers=[]; report={'image':image,'storage':'PostgreSQL' if database_options else 'SQLite','gates':[]}
    output=ROOT/'build/quickstart';output.mkdir(parents=True,exist_ok=True)
    (output/('smoke-postgresql.json' if database_options else 'smoke.json')).unlink(missing_ok=True)
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
        admin=tokens['admin']
        snapshot=ok(api('/api/control/v1/config/export',admin))
        assert all(g['id'].startswith('standard-') for g in snapshot['grants']) and snapshot['delegations']==[]
        assert all(not tool['enabled'] for tool in snapshot['tools'])
        for key,default,suffix in [('agentGroups','default-agents','AgentGroup'),('toolGroups','default-tools','ToolGroup'),('deviceGroups','default-devices','DeviceGroup')]:
            assert {x['id'] for x in snapshot[key]}=={default,*[tier+suffix for tier in ('ReadOnly','ReadAndWrite','Admin')]}
            member={'agentGroups':'agentIds','toolGroups':'toolIds','deviceGroups':'deviceIds'}[key]
            assert all(not row[member] for row in snapshot[key] if row['id']!=default)
        mark('fresh reviewed installation/independent identities/default groups/zero runtime rights')
        # The actual native executable owns this CSR/key and advertises actual installed binary profiles.
        deadline=time.monotonic()+45
        while True:
            pending=ok(api('/api/control/v1/endpoint/enrollments',admin))['items']
            if pending:break
            assert time.monotonic()<deadline,'Native executor did not begin enrollment'
            time.sleep(.5)
        enrollment=pending[0];device=enrollment['deviceId']
        review=ok(api('/api/control/v1/endpoint/enrollments/review?code='+enrollment['userCode'],admin))
        ok(api('/api/control/v1/endpoint/enrollments/decision',admin,
            {'userCode':review['userCode'],'keyFingerprint':review['keyFingerprint'],'choice':'APPROVE'},'POST'))
        deadline=time.monotonic()+45
        while True:
            managed=ok(api('/api/control/v1/endpoint/devices/'+device,admin))
            if managed.get('lastSeenUnixMs',0)>0:break
            assert time.monotonic()<deadline,('Native mTLS check-in missing',managed)
            time.sleep(.5)
        assert device in ok(api('/api/control/v1/device-groups/default-devices',admin))['deviceIds']
        assert ok(api('/api/quickstart/v1/tools',admin))['tools']==[]
        mark('real native CSR/enrollment/key-bound mTLS/default membership/no implicit discovery')
        # Keep the maker/checker regression scoped. Reviewer 2 is the installation
        # owner; admin and reviewer 1 use the same full-scope restrictive role.
        # This also proves the live Super Admin exception uses the ordinary APIs.
        management=ok(api('/api/control/v1/roles/installation-management',admin))
        super_role={**management,'id':'smoke-super-management','name':'Smoke installation owner','revision':1,'managementRules':[]}
        ok(api('/api/control/v1/roles',admin,super_role,'POST'),201)
        ok(api('/api/control/v1/teams',admin,dict(id='smoke-super-team',name='Smoke installation owners',
            enabled=True,revision=1,userIds=['reviewer-2'],roleIds=[super_role['id']]),'POST'),201)
        ok(api('/api/control/v1/roles/'+management['id'],admin,{**management,'portalRole':'ADMINISTRATOR'},
            'PUT',revision=management['revision']))
        def transition(change,action,user):
            return ok(api('/api/control/v1/configuration-changes/'+change['id']+'/transition',tokens[user],
                {'action':action,'expectedRevision':change['revision']},'POST'))
        def reviewed(path,document,method='POST',revision=None):
            key=secrets.token_hex(16)
            change=ok(api(path,admin,document,method,key,revision),202)
            assert ok(api(path,admin,document,method,key,revision),202)['id']==change['id']
            change=transition(change,'SUBMIT','admin')
            denied=api('/api/control/v1/configuration-changes/'+change['id']+'/transition',admin,
                {'action':'APPROVE','expectedRevision':change['revision']},'POST')
            assert denied[0]==403,denied
            for reviewer in ('reviewer-1','reviewer-2')[:change['requiredReviews']]:
                change=transition(change,'APPROVE',reviewer)
            return transition(change,'APPLY','admin')
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
        calc=ok(api('/api/control/v1/tools/calculator.evaluate',admin))
        reviewed('/api/control/v1/tools/'+calc['id'],{**calc,'enabled':True},'PUT',calc['revision'])
        # Complete HUMAN grant; management Role alone never supplies this runtime capability.
        conditions=dict(notBeforeUnixMs=0,expiresAtUnixMs=0,networkCidrs=[],devicePosture=[],regions=[],
                        hoursUtc=[],requireOnline=True,highRisk=False)
        scope=dict(toolGroups=dict(ids=['default-tools'],all=False),
                   deviceGroups=dict(ids=['default-devices'],all=False),actions=['evaluate'],allActions=False,
                   resources=[dict(kind='CUSTOM',locator='builtin/calculator.evaluate',match='EXACT')],conditions=conditions)
        grant=dict(id='human-calculator',name='Reviewed calculator grant',enabled=True,revision=1,
                   sourceType='TEAM',sourceId='installation-administrators',purpose='HUMAN',scope=scope)
        reviewed('/api/control/v1/grants',grant)
        tools=ok(api('/api/quickstart/v1/tools',admin))['tools']
        assert {t['toolId'] for t in tools}=={'calculator.evaluate'},tools
        mark('HTTP maker/checker/idempotent drafts/self-review rejection/complete grant discovery')
        timings=[]
        for index in range(108):
            start=time.perf_counter_ns();ok(api('/api/quickstart/v1/tools',admin));elapsed=(time.perf_counter_ns()-start)/1e6
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
        reviewed('/api/control/v1/workload-bindings',dict(id='workload-local',name='Reviewed diagnostic workload',
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
        assert {t['name'] for t in ok(mcp('tools/list'))['result']['tools']}=={'calculator.evaluate'}
        service_key=secrets.token_hex(16)
        service_result=ok(mcp('tools/call',{'name':'calculator.evaluate','arguments':{'expression':'7*2'}},service_key))
        assert service_result['result']['structuredContent']['value']==14,service_result
        assert ok(mcp('tools/call',{'name':'calculator.evaluate','arguments':{'expression':'7*2'}},service_key))['result']==service_result['result']
        service_grant=ok(api('/api/control/v1/grants/service-service',admin))
        reviewed('/api/control/v1/grants/service-service',{**service_grant,'enabled':False},'PUT',service_grant['revision'])
        assert ok(mcp('tools/list'))['result']['tools']==[]
        assert mcp('tools/call',{'name':'calculator.evaluate','arguments':{'expression':'7*2'}},service_key)[0]==403
        mark('real authenticated Gateway SERVICE/group capability/native effect/exact retry/live revocation')
        if gateway_image:
            identity=next(i for i in ok(api('/api/control/v1/identity-bindings',admin))['items'] if i['subject']=='admin')
            reviewed('/api/control/v1/workload-bindings',dict(id='workload-delegated',name='Verified human delegation',
                enabled=True,revision=1,agentId='agent-local',mode='DELEGATED',issuer='gateway',subject='local-workload',
                audience='gateway',credentialSha256=delegated_digest,credentialEpoch=1,
                expiresAtUnixMs=int(time.time()*1000)+3600000,delegatedUserId=identity['userId'],delegatedSessionEpoch=identity['sessionEpoch']))
            assert ok(mcp('tools/list',token=delegated_token))['result']['tools']==[]
            reviewed('/api/control/v1/delegations',dict(id='human-delegation',name='Same Team and Agent Group',
                enabled=True,revision=1,teamId='installation-administrators',agentGroupId='default-agents',scope=scope))
            assert {t['name'] for t in ok(mcp('tools/list',token=delegated_token))['result']['tools']}=={'calculator.evaluate'}
            result=ok(mcp('tools/call',{'name':'calculator.evaluate','arguments':{'expression':'8*3'}},token=delegated_token))
            assert result['result']['structuredContent']['value']==24
            delegation=ok(api('/api/control/v1/delegations/human-delegation',admin))
            reviewed('/api/control/v1/delegations/human-delegation',{**delegation,'enabled':False},'PUT',delegation['revision'])
            assert ok(mcp('tools/list',token=delegated_token))['result']['tools']==[]
            assert mcp('tools/call',{'name':'calculator.evaluate','arguments':{'expression':'8*3'}},token=delegated_token)[0]==403
            mark('real DELEGATED identity/same Team and Agent Group/native effect/current delegation revocation')
        # Configure the diagnostic workload outside the mimic script. The script only calls/logs.
        for tool_id in ('hotfolder.write_text','client.read_log_entry'):
            tool=ok(api('/api/control/v1/tools/'+tool_id,admin))
            reviewed('/api/control/v1/tools/'+tool_id,{**tool,'enabled':True},'PUT',tool['revision'])
        diagnostic_scope={**scope,'actions':['write','read'],'resources':[
            dict(kind='FILE',locator='rahul-nigam.txt',match='EXACT'),
            dict(kind='CUSTOM',locator='client/packets/latest',match='EXACT')]}
        for purpose in ('CAPABILITY','SERVICE'):
            reviewed('/api/control/v1/grants',dict(id='diagnostic-'+purpose.lower(),name='Bounded diagnostic '+purpose,
                enabled=True,revision=1,sourceType='AGENT_GROUP',sourceId='default-agents',purpose=purpose,scope=diagnostic_scope))
        with tempfile.TemporaryDirectory(prefix='toolgate-mimic-credential-') as private:
            token_file=Path(private)/'token';token_file.write_text(service_token,encoding='utf-8');token_file.chmod(0o600)
            result=run([sys.executable,str(ROOT/'debug/agent-mimic.py'),'--gateway',mcp_origin,'--token-file',str(token_file)],capture_output=True,text=True)
            assert service_token not in result.stdout and service_token not in result.stderr
            output=ROOT/'build/quickstart';output.mkdir(parents=True,exist_ok=True)
            (output/'agent-mimic.log').write_text(result.stdout+result.stderr,encoding='utf-8')
        assert mcp('tools/call',{'name':'hotfolder.write_text','arguments':{'path':'other.txt','text':'denied'}})[0]==403
        diagnostic=ok(api('/api/control/v1/grants/diagnostic-service',admin))
        reviewed('/api/control/v1/grants/diagnostic-service',{**diagnostic,'enabled':False},'PUT',diagnostic['revision'])
        assert ok(mcp('tools/list'))['result']['tools']==[]
        mark('unchanged call-and-log agent mimic/scoped file/one redacted log entry/ungranted file denied')
        def invoke(arguments,key=None):
            return api('/api/quickstart/v1/invoke',admin,
                       {'toolId':'calculator.evaluate','arguments':arguments},'POST',key)
        key=secrets.token_hex(16);args={'expression':'2+3*4'}
        outcome=ok(invoke(args,key));invocation=outcome['invocation']
        assert invocation['evaluation']['context']['mode']=='HUMAN'
        assert invocation['evaluation']['context']['userId']=='admin'
        deadline=time.monotonic()+45
        while True:
            result=api('/api/control/v1/access/human/invocations/'+invocation['id']+'/result',admin)
            if result[0]==200 and result[1].get('result') is not None:break
            assert time.monotonic()<deadline,('Effect did not complete',result)
            time.sleep(.5)
        assert result[1]['result']['output']['value']==14,result
        assert ok(invoke(args,key))['invocation']['id']==invocation['id']
        assert invoke({'expression':'99'},key)[0]==409
        saved=ok(api('/api/control/v1/access/invocations/'+invocation['id'],admin))
        assert saved['state']=='SUCCEEDED',saved
        mark('direct HUMAN relay/actual native effect/signed permit/durable result/exact retry conflict')
        # ASK is discoverable but supplies no execution permit; a different person must review.
        policy=dict(id='calculator-review',name='Independent calculation review',enabled=True,revision=1,
                    scope=scope,decision='ASK',teams=dict(ids=[],all=True),agentGroups=dict(ids=[],all=True),approverTeams=dict(ids=['installation-administrators'],all=False))
        reviewed('/api/control/v1/policies',policy)
        ask_key=secrets.token_hex(16);ask=ok(invoke(args,ask_key),202)['invocation']
        assert ask['state']=='PENDING_APPROVAL'
        approvals=ok(api('/api/control/v1/approvals',tokens['reviewer-1']))['items']
        approval=next(a for a in approvals if a['invocationId']==ask['id'])
        decision=dict(obligationId='calculator-review',expectedRevision=approval['revision'],decision='APPROVE')
        assert api('/api/control/v1/approvals/'+approval['id']+'/decision',admin,decision,'POST')[0]==403
        ok(api('/api/control/v1/approvals/'+approval['id']+'/decision',tokens['reviewer-1'],decision,'POST'))
        ok(invoke(args,ask_key))
        deadline=time.monotonic()+45
        while True:
            result=api('/api/control/v1/access/human/invocations/'+ask['id']+'/result',admin)
            if result[0]==200 and result[1].get('result') is not None:break
            assert time.monotonic()<deadline,result
            time.sleep(.5)
        mark('ASK discovery/exact operation approval/independent reviewer/approved effect')
        # Revocation is current online authority, even for saved results or an identical retry.
        current=ok(api('/api/control/v1/grants/'+grant['id'],admin))
        reviewed('/api/control/v1/grants/'+grant['id'],{**current,'enabled':False},'PUT',current['revision'])
        assert ok(api('/api/quickstart/v1/tools',admin))['tools']==[]
        assert invoke(args,key)[0]==403
        assert api('/api/control/v1/access/human/invocations/'+invocation['id']+'/result',admin)[0]==403
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
        # A restricted administrator cannot replace a graph containing a Super Admin role.
        # The installation owner can apply the import directly, with canonical lifecycle checks and audit.
        import_body=dict(snapshot=proposed,mode='REPLACE',dryRun=False)
        assert api('/api/control/v1/config/import',admin,import_body,'POST',revision=baseline['revision'])[0]==403
        ok(api('/api/control/v1/config/import',tokens['reviewer-2'],import_body,'POST',revision=baseline['revision']))
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
        assert ok(api('/api/quickstart/v1/tools',admin))['tools']==[]
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
                 'TOOLGATE_ENTERPRISE_PASSWORD':passwords['admin'],
                 'TOOLGATE_ENTERPRISE_REVIEWER_1':passwords['reviewer-1'],
                 'TOOLGATE_ENTERPRISE_REVIEWER_2':passwords['reviewer-2']})
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
    parser=argparse.ArgumentParser();parser.add_argument('--image',default='olo-toolgate-quickstart:enterprise-validation');parser.add_argument('--build',action='store_true');parser.add_argument('--build-only',action='store_true');parser.add_argument('--no-browser',action='store_true');parser.add_argument('--postgresql',action='store_true');parser.add_argument('--gateway-image');parser.add_argument('--client-assets',type=Path);parser.add_argument('--prebuilt-ui',action='store_true');args=parser.parse_args()
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
                smoke(args.image,not args.no_browser,['--network',network,'--env-file',str(env)],args.gateway_image)
            finally:
                subprocess.run(['docker','network','disconnect',network,db['container']],capture_output=True)
                subprocess.run(['docker','network','rm',network],capture_output=True)
    else:smoke(args.image,not args.no_browser,gateway_image=args.gateway_image)


if __name__=='__main__':main()
