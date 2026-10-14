# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Production Control replicas on isolated PostgreSQL with externally reviewed group authority."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
import json
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from control.check import ROOT,database,ready,request,run
from access.presets import load as initial_configuration


def protected_defaults(baseline):
    """Verify release presets without treating empty preset groups as active access."""
    expected=initial_configuration()['records']
    def indexed(rows):
        records={row['id']:row for row in rows}
        assert len(records)==len(rows),'Duplicate initial records'
        return records
    for field in ('grants','policies'):
        assert indexed(baseline[field])==indexed(expected[field]),'Unexpected initial '+field
    for field,extra in (('roles','installation-management'),('bindings','binding-default')):
        actual=indexed(baseline[field]);configured=indexed(expected[field])
        assert set(actual)==set(configured)|{extra},'Unexpected initial '+field
        assert all(actual[key]==value for key,value in configured.items()),'Changed initial '+field
    for field,default,member in (('teams','team-default','userIds'),
                               ('agentGroups','default-agents','agentIds'),
                               ('toolGroups','default-tools','toolIds'),
                               ('deviceGroups','default-devices','deviceIds')):
        actual=indexed(baseline[field]);configured=indexed(expected[field])
        assert set(actual)==set(configured)|({'installation-administrators'} if field=='teams' else set()),'Unexpected initial '+field
        for key,value in configured.items():
            if key==default:continue  # Signed installation membership is retained in default groups.
            assert actual[key]==value and not actual[key][member],'Unexpected populated preset '+key
    for field in ('delegations','agentDelegations','workloadBindings','devices','deviceEvidence'):
        assert not baseline[field],'Unexpected initial '+field
    assert all(not tool['enabled'] for tool in baseline['tools']),'Initially enabled tool'
    assert all(not agent['enabled'] for agent in baseline['agents']),'Initially enabled agent'


def scoped_reviewers(api,baseline):
    """Keep one installation owner while testing ordinary maker/checker authority."""
    management=next(role for role in baseline['roles'] if role['id']=='installation-management')
    assert management['portalRole']=='SUPER_ADMIN'
    owner={**management,'id':'smoke-super-management','name':'Smoke installation owner','revision':1,'managementRules':[]}
    assert api('/api/control/v1/roles',body=owner,method='POST')[0]==201
    assert api('/api/control/v1/teams',body=dict(id='smoke-super-team',name='Smoke installation owners',
        enabled=True,revision=1,userIds=['reviewer-2'],roleIds=[owner['id']]),method='POST')[0]==201
    assert api('/api/control/v1/roles/'+management['id'],body={**management,'portalRole':'ADMINISTRATOR'},
        method='PUT',revision=management['revision'])[0]==200


@contextmanager
def fixture(image,helper_image='olo-toolgate-quickstart:enterprise-validation',replicas=1):
    """Helper generates custody; production verifies its signed installation itself."""
    name='toolgate-control-proof-'+secrets.token_hex(6);volume=name+'-custody';containers=[]
    with database() as db,tempfile.TemporaryDirectory(prefix='toolgate-control-custody-') as temp:
        folder=Path(temp);run(['docker','volume','create',volume],capture_output=True)
        def helper(code,envfile=None):
            return run(['docker','run','--rm','--network','container:'+db['container'],
                *(['--env-file',str(envfile)] if envfile else []),'-v',volume+':/data',
                '--entrypoint','/opt/quickstart-python/bin/python',helper_image,'-c',
                "import sys;sys.path.insert(0,'/opt/quickstart');import supervisor as s;"+code],
                capture_output=True,text=True).stdout.strip()
        try:
            env=json.loads(helper("s.initialize();import json;print(json.dumps({k:v for k,v in s.configure()[0].items() if k.startswith(('QUARKUS_','MP_JWT_','TOOLGATE_'))}))"))
            env.update(TOOLGATE_QUICKSTART_ENABLED='false',QUARKUS_PROFILE='prod',
                QUARKUS_FLYWAY_ACTIVE='true',QUARKUS_FLYWAY_MIGRATE_AT_START='true',
                QUARKUS_DATASOURCE_ACTIVE='true',QUARKUS_DATASOURCE_HEALTH_ENABLED='true',
                QUARKUS_DATASOURCE_JDBC_URL='jdbc:postgresql://127.0.0.1:5432/control?sslmode=disable',
                QUARKUS_DATASOURCE_USERNAME='control_app',QUARKUS_DATASOURCE_PASSWORD=db['CONTROL_TEST_PASSWORD'],
                QUARKUS_FLYWAY_USERNAME='control_migrator',QUARKUS_FLYWAY_PASSWORD=db['CONTROL_TEST_PASSWORD'],
                QUARKUS_MANAGEMENT_HOST='0.0.0.0',TOOLGATE_CONTROL_DEVELOPMENT_MODE='true',
                TOOLGATE_CONTROL_FLEET_ENABLED='false',TOOLGATE_CONTROL_ENDPOINT_ENABLED='false',
                TOOLGATE_QUICKSTART_DATABASE_MODE='postgresql')
            envfile=folder/'runtime.env'
            def save_env(values):
                envfile.write_text('\n'.join(k+'='+v for k,v in values.items())+'\n',encoding='utf-8');envfile.chmod(0o600)
            for index in range(replicas):
                values={**env,'QUARKUS_HTTP_PORT':str(8082+3*index),'QUARKUS_MANAGEMENT_PORT':str(9092+3*index),'QUARKUS_HTTP_SSL_PORT':str(8443+index)};save_env(values)
                container=run(['docker','run','-d','--name',name+'-'+str(index),'--read-only','--cap-drop=ALL',
                    '--security-opt=no-new-privileges','--memory=768m','--cpus=2','--network','container:'+db['container'],
                    '--tmpfs','/tmp:rw,noexec,nosuid,size=128m,uid=65532,gid=65532','--env-file',str(envfile),
                    '-v',volume+':/data:ro',image],capture_output=True,text=True).stdout.strip()
                containers.append(container)
                ready('http://127.0.0.1:'+db['managementPort' if index==0 else 'replicaManagementPort'])
            save_env(env)
            tokens={user:helper("print(s.jwt(s.GROUPS,'"+user+"'))",envfile) for user in ('admin','reviewer-1','reviewer-2')}
            origins=['http://127.0.0.1:'+db['runtimePort']]
            if replicas==2:origins.append('http://127.0.0.1:'+db['replicaRuntimePort'])
            yield dict(containers=containers,origins=origins,tokens=tokens,db=db,folder=folder)
        except Exception:
            output=ROOT/'build/control';output.mkdir(parents=True,exist_ok=True)
            for index,container in enumerate(containers):
                subprocess.run(['docker','kill','--signal','QUIT',container],capture_output=True)
                logs=subprocess.run(['docker','logs',container],capture_output=True,text=True)
                (output/('failure-'+str(index)+'.log')).write_text(logs.stdout+logs.stderr,encoding='utf-8')
            raise
        finally:
            for container in reversed(containers):subprocess.run(['docker','rm','-f',container],capture_output=True)
            subprocess.run(['docker','volume','rm',volume],capture_output=True)


def smoke(image,helper_image='olo-toolgate-quickstart:enterprise-validation',replicas=2):
    with fixture(image,helper_image,replicas) as f:
        origins=f['origins'];tokens=f['tokens'];secret_values=list(tokens.values())+[f['db']['CONTROL_TEST_PASSWORD']]
        def api(path,user='admin',body=None,method='GET',key=None,origin=0,revision=None):
            headers={'Idempotency-Key':key or secrets.token_hex(16)} if body is not None else {}
            if revision is not None:headers['If-Match']='"'+str(revision)+'"'
            status,raw,_=request(origins[origin]+path,tokens.get(user),body,method,
                headers or None)
            return status,json.loads(raw) if raw else None
        def ok(value,status=200):assert value[0]==status,(value[0],value[1]);return value[1]
        assert api('/api/control/v1/users','anonymous')[0]==401
        baseline=ok(api('/api/control/v1/config/export'))
        protected_defaults(baseline)
        scoped_reviewers(api,baseline)
        entity=dict(id='replica-created-user',name='Reviewed employee',enabled=False,revision=1);key=secrets.token_hex(16)
        with ThreadPoolExecutor(max_workers=4) as pool:
            responses=list(pool.map(lambda n:api('/api/control/v1/users',body=entity,method='POST',key=key,origin=n%len(origins)),range(4)))
        changes=[ok(value,202) for value in responses];assert len({c['id'] for c in changes})==1;change=changes[0]
        def transition(action,user='admin',origin=0):
            nonlocal change
            change=ok(api('/api/control/v1/configuration-changes/'+change['id']+'/transition',user,
                dict(action=action,expectedRevision=change['revision']),'POST',origin=origin))
        transition('SUBMIT')
        assert api('/api/control/v1/configuration-changes/'+change['id']+'/transition',body=dict(action='APPROVE',expectedRevision=change['revision']),method='POST')[0]==403
        # The review threshold in force at creation decides how many independent reviewers approve.
        for index,reviewer in enumerate(('reviewer-1','reviewer-2')[:change['requiredReviews']]):
            transition('APPROVE',reviewer,(len(origins)-1-index)%len(origins))
        transition('APPLY')
        for origin in range(len(origins)):
            assert ok(api('/api/control/v1/users/'+entity['id'],origin=origin))['enabled'] is False
            assert entity['id'] in ok(api('/api/control/v1/teams/team-default',origin=origin))['userIds']
        epoch=ok(api('/api/control/v1/access/status'))['authorizationEpoch']
        for index,container in enumerate(f['containers']):
            inspect=json.loads(run(['docker','inspect',container],capture_output=True,text=True).stdout)[0]
            assert inspect['Config']['User']=='65532:65532' and inspect['HostConfig']['ReadonlyRootfs']
            assert request(origins[index]+'/q/health/ready')[0]==404
            management='http://127.0.0.1:'+f['db']['managementPort' if index==0 else 'replicaManagementPort']
            assert request(management+'/q/metrics')[0]==200
            run(['docker','restart','-t','30',container],capture_output=True);ready(management)
            assert ok(api('/api/control/v1/access/status',origin=index))['authorizationEpoch']>=epoch
            assert ok(api('/api/control/v1/users/'+entity['id'],origin=index))['enabled'] is False
            run(['docker','stop','-t','30',container],capture_output=True)
            state=json.loads(run(['docker','inspect',container],capture_output=True,text=True).stdout)[0]['State'];assert state['ExitCode'] in (0,143) and not state['OOMKilled'],state
            logs=run(['docker','logs',container],capture_output=True,text=True);combined=logs.stdout+logs.stderr
            assert all(value not in combined for value in secret_values) and 'stopped in' in combined
            run(['docker','cp',container+':/usr/share/licenses/olo-toolgate/third-party/gradle.lockfile',str(f['folder']/'image.lock')],capture_output=True)
            assert (f['folder']/'image.lock').read_bytes()==(ROOT/'apps/control-plane/gradle.lockfile').read_bytes()
        info=json.loads(run(['docker','image','inspect',image],capture_output=True,text=True).stdout)[0]
        output=ROOT/'build/control';output.mkdir(parents=True,exist_ok=True)
        (output/'container-smoke.json').write_text(json.dumps(dict(image=image,imageId=info['Id'],replicas=replicas,
            realPostgres=True,nonRoot=True,readOnlyRoot=True,independentConfigurationReview=True,
            sharedIdempotency=True,protectedDefaults=True,monotonicRestart=True,redactedLogs=True,
            separateManagement=True,gracefulShutdown=True,thirdPartyNotices=True,
            databaseTransport='fixture-only loopback; production requires verify-full'),indent=2)+'\n',encoding='utf-8')
        print('Production Control replicas/PostgreSQL/reviewed authority/races/restart/custody/shutdown passed')


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--image',default='olo-toolgate-control:module05');p.add_argument('--helper-image',default='olo-toolgate-quickstart:enterprise-validation');p.add_argument('--no-build',action='store_true');p.add_argument('--client-assets',type=Path);p.add_argument('--prebuilt-ui',action='store_true');args=p.parse_args()
    if not args.no_build:
        assets=[]
        if args.client_assets:
            relative=args.client_assets.resolve().relative_to(ROOT).as_posix()
            if not (args.client_assets/'manifest.json').is_file():raise ValueError('Verified public bundle required')
            assets=['--build-arg','CLIENT_ASSETS_DIR='+relative,'--build-arg','CLIENT_DOWNLOADS_DIRECTORY=/opt/toolgate/client-downloads']
        if args.prebuilt_ui: assets += ['--build-arg','ADMIN_UI_SOURCE=console-prebuilt']
        run(['docker','build','-f','apps/control-plane/Dockerfile','--build-arg','VERSION='+(ROOT/'VERSION').read_text().strip(),
            '--build-arg','REVISION='+run(['git','rev-parse','HEAD'],capture_output=True,text=True).stdout.strip(),*assets,'-t',args.image,'.'])
    smoke(args.image,args.helper_image)
if __name__=='__main__':main()
