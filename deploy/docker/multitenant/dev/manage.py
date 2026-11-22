# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Operate the shared deployment without generating identities or replaying work."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import uuid

HERE=Path(__file__).resolve().parent
IMAGE_KEYS={'CONTROL_IMAGE','GATEWAY_IMAGE','TOOL_HOST_IMAGE','RUNTIME_IMAGE','POSTGRES_IMAGE','INGRESS_IMAGE'}
IMPORT_CASES={'incident':'tenant-incident-triage','device-file':'device-file-processing',
              'authority':'governed-file-processing','publication-a':'publication-switch',
              'publication-b':'publication-switch','portable':'portable-summary','log-triage':'log-triage'}


def read_settings(path):
    result={}
    for line in path.read_text(encoding='utf-8-sig').splitlines():
        line=line.strip()
        if not line or line.startswith('#'):continue
        key,separator,value=line.partition('=')
        if not separator or key in result:raise ValueError('Use unique KEY=value settings')
        result[key]=value.strip().strip('"').strip("'")
    return result


def settings(home=HERE):
    result=read_settings(home/'.env.example')
    if (home/'.env').exists():result.update(read_settings(home/'.env'))
    if set(result)!=IMAGE_KEYS or any(not value or any(c.isspace() for c in value) for value in result.values()):
        raise ValueError('Set only the six image references in .env; custody belongs in credentials/')
    return result


def required(root,name):
    path=root/name
    if not path.is_file() or path.is_symlink() or not path.resolve().is_relative_to(root.resolve()):
        raise ValueError('Missing or linked custody file: '+name)
    if path.stat().st_size==0:raise ValueError('Empty custody file: '+name)
    return path


def validate(home=HERE):
    root=home/'credentials'
    for name in ('database/admin-password','database/migrator-password','database/runtime-password',
                 'database/platform-password','database/server.crt','database/server.key',
                 'control/control.env','control/manifest.json','control/database-ca.pem',
                 'control/platform-password','ingress/tls.crt','ingress/tls.key','ingress/control-ca.pem',
                 'graph-builder/graph.json','graph-builder/ca.crt','graph-builder/server.crt',
                 'graph-builder/server.key','graph-builder/control-ca.crt'):
        required(root,name)
    passwords=[required(root,'database/'+name+'-password').read_bytes().strip()
               for name in ('admin','migrator','runtime','platform')]
    if any(not value for value in passwords) or len(set(passwords))!=4:
        raise ValueError('Use four independent nonempty database passwords')
    if required(root,'control/platform-password').read_bytes().strip()!=passwords[3]:
        raise ValueError('Control platform custody does not match database provisioning')
    control=read_settings(root/'control/control.env')
    expected={
        'QUARKUS_DATASOURCE_USERNAME':'control_app',
        'QUARKUS_FLYWAY_USERNAME':'control_migrator',
        'TOOLGATE_CONTROL_PLATFORM_USERNAME':'control_provisioner',
        'TOOLGATE_CONTROL_PLATFORM_PASSWORD_PATH':'/etc/toolgate/platform-password',
        'TOOLGATE_INITIALIZE_MANIFEST':'/etc/toolgate/manifest.json',
        'TOOLGATE_CONTROL_RUNTIME_BUILDER_ORIGIN':'https://graph-builder:19460',
    }
    if any(control.get(key)!=value for key,value in expected.items()):
        raise ValueError('Control principal, initialization or graph-builder settings do not match this deployment')
    if (control.get('QUARKUS_DATASOURCE_PASSWORD','').encode()!=passwords[2]
            or control.get('QUARKUS_FLYWAY_PASSWORD','').encode()!=passwords[1]):
        raise ValueError('Control runtime/migration custody does not match database provisioning')
    url=control.get('QUARKUS_DATASOURCE_JDBC_URL','')
    if (not url.startswith('jdbc:postgresql://postgres:5432/toolgate?')
            or 'sslmode=verify-full' not in url.split('?',1)[-1].split('&')
            or 'sslrootcert=/etc/toolgate/database-ca.pem' not in url.split('?',1)[-1].split('&')):
        raise ValueError('Use verified PostgreSQL TLS with the mounted database CA')
    manifest=json.loads(required(root,'control/manifest.json').read_text(encoding='utf-8-sig'))
    tenants=manifest.get('tenants',[])
    if manifest.get('formatVersion')!=1 or len(tenants)!=2:
        raise ValueError('Provide the reviewed two-tenant initialization manifest')
    by_host={host:row for row in tenants for host in row['hosts']}
    ids=[]
    graph=json.loads(required(root,'graph-builder/graph.json').read_text(encoding='utf-8-sig'))
    if 'controlOrigin' in graph:raise ValueError('Use an origin and tokenFile per graph reader')
    readers=graph.get('readers',{})
    graph_settings=dict(host='0.0.0.0',port=19460,certificate='/installation/server.crt',
                        key='/installation/server.key',clientCa='/installation/ca.crt',
                        controlCaFile='/installation/control-ca.crt')
    if any(graph.get(key)!=value for key,value in graph_settings.items()) or not graph.get('clientNames'):
        raise ValueError('Configure the private graph listener and explicit mTLS Control identities')
    for letter,host in (('a','tenant-a.olo.test'),('b','tenant-b.olo.test')):
        tenant=by_host.get(host)
        if tenant is None:raise ValueError('Manifest must register both documented tenant hosts')
        # The initializer allocates identity from reviewed administrator packets;
        # its manifest intentionally does not accept an arbitrary tenantId field.
        packet_ref=tenant['administratorProvisioningRef']
        if not isinstance(packet_ref,str) or not packet_ref or '/' in packet_ref or '\\' in packet_ref:
            raise ValueError('Use a local administrator provisioning reference')
        required(root,'control/'+packet_ref+'/packet.json')
        required(root,'control/'+packet_ref+'/trust.json')
        # Packet parsing/signature validation is owned by Control, not this wrapper.
        # Reader ownership comes from the Gateway's explicit UUID and is checked
        # against the signed packet by Control during actual initialization.
        gateway=json.loads(required(root,'tenant-'+letter+'/gateway/config.json').read_text(encoding='utf-8-sig'))
        tenant_id=gateway['control']['tenantId']
        if str(uuid.UUID(tenant_id))!=tenant_id:raise ValueError('Use canonical tenant UUIDs')
        ids.append(tenant_id)
        if (gateway['listen']!='0.0.0.0:8081' or gateway['managementListen']!='0.0.0.0:9091'
                or gateway.get('toolHosts') or gateway.get('trustedTlsProxy') is not True):
            raise ValueError('Use the documented public/management listeners, pull mode and trusted TLS ingress')
        endpoints=tenant['endpoints']
        control_origin='https://'+host+(':'+str(18443 if letter=='a' else 19443))
        gateway_origin='https://'+host+(':'+str(18444 if letter=='a' else 19444))
        if endpoints['controlUrl']!=control_origin or endpoints['gatewayUrl']!=gateway_origin:
            raise ValueError('Tenant endpoint ports must match the shared ingress')
        if gateway['control']['url']!=control_origin or gateway['publicDiscovery']!=dict(controlOrigin=control_origin,gatewayOrigin=gateway_origin):
            raise ValueError('Gateway configuration must use its own canonical tenant origins')
        required(root,'tenant-'+letter+'/gateway/credentials.json')
        model=read_settings(required(root,'tenant-'+letter+'/gateway/model.env'))
        if not model.get('TOOLGATE_WORKFLOW_MODEL_ORIGINS'):
            raise ValueError('Explicitly configure each tenant Gateway model origin allowlist')
        required(root,'tenant-'+letter+'/host/config.json')
        required(root,'tenant-'+letter+'/worker/worker.token')
        required(root,'tenant-'+letter+'/worker/ca.crt')
        reader=readers.get(tenant_id)
        if reader!=dict(origin=control_origin,tokenFile='/installation/reader-'+letter+'.token'):
            raise ValueError('Bind each graph reader to its own canonical Control origin and token file')
        reader_token=required(root,'graph-builder/reader-'+letter+'.token').read_bytes().strip()
        if reader_token!=required(root,'tenant-'+letter+'/worker/worker.token').read_bytes().strip():
            raise ValueError('Graph reader and worker must use the matching tenant workload credential')
    if len(set(ids))!=2 or set(readers)!=set(ids):
        raise ValueError('Use exactly two distinct tenant identities and graph readers')
    return root


def command(operation,home=HERE):
    env=dict(os.environ,**settings(home),CREDENTIALS_DIR=str((home/'credentials').resolve()))
    base=['docker','compose','--project-name','olo-multitenant-dev','--file',str(home/'compose.yaml')]
    if operation in ('start','validate'):
        validate(home)
        subprocess.run(base+['config','--quiet'],env=env,cwd=home,check=True)
    if operation=='start':
        subprocess.run(['docker','run','--rm','--network','none','--entrypoint','/opt/toolgate-runtime/bin/python',
            env['RUNTIME_IMAGE'],'-c',"import inspect;from toolgate_runtime.graph_runtime import ControlCandidates;assert 'origin' not in inspect.signature(ControlCandidates).parameters"],
            env=env,cwd=home,check=True)
        subprocess.run(base+['up','-d'],env=env,cwd=home,check=True)
    elif operation=='build-runtime':
        root=Path(__file__).resolve().parents[4]
        revision=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()
        subprocess.run(['docker','build','--build-arg','VERSION='+ (root/'VERSION').read_text().strip(),
            '--build-arg','REVISION='+revision,'--tag',env['RUNTIME_IMAGE'],str(root/'apps/langgraph-runtime')],
            env=env,cwd=home,check=True)
    elif operation=='stop':subprocess.run(base+['stop'],env=env,cwd=home,check=True)
    elif operation=='status':subprocess.run(base+['ps'],env=env,cwd=home,check=True)
    print({'start':'Deployment started; inspect actual readiness and tenant authorization before importing bots.',
           'validate':'Custody layout and Compose structure validated; signatures, live TLS and execution remain runtime checks.',
           'stop':'Deployment stopped; volumes and credentials retained.',
           'build-runtime':'Local runtime built from this working tree; this is not release qualification.',
           'status':'Status inspected.'}[operation])


def choose_use_case():
    if not sys.stdin.isatty():return 'none'
    choices={'0':'none','1':'incident','2':'log-triage','3':'mcp-proxy','4':'device-file',
             '5':'authority','6':'publication-a','7':'publication-b','8':'portable'}
    print('Use case after infrastructure startup:')
    print('  (0) Infrastructure only [default]')
    print('  (1) Tenant incident triage — import your exported .olo')
    print('  (2) Scoped log triage — import .olo; requires prepared tenant client devices')
    print('  (3) Govern existing upstream MCP tools — show setup instructions')
    print('  (4) Governed device file processing — import .olo')
    print('  (5) Authority enforcement — import governed file processing .olo')
    print('  (6) Publication switching — import revision A .olo')
    print('  (7) Publication switching — import revision B .olo (replaces A)')
    print('  (8) Portable summary — import your saved/exported .olo')
    while True:
        selected=input('Select use case [0]: ').strip() or '0'
        if selected in choices:return choices[selected]
        print('Choose a listed use case from 0 to 8')


def import_bot(path,tenants,use_case,home=HERE):
    root=Path(__file__).resolve().parents[4]
    sys.path.insert(0,str(root))
    from bot.bundle import MAX_BUNDLE,verify
    from tools.admin.control import Control,Refused,HttpRefused,read_file
    from tools.admin.import_workflow import deploy
    raw=read_file(path,MAX_BUNDLE)
    expected=IMPORT_CASES[use_case]
    if verify(raw)['workflowId']!=expected:raise ValueError('The selected bundle does not match the use case')
    # Load both operator token files before starting any import. Actual authority
    # is checked by each tenant independently; this is not a cross-tenant atomic
    # import. Tokens are never supplied by the portable bot.
    clients=[]
    for letter in tenants:
        own=home/'credentials'/('tenant-'+letter)
        origin='https://tenant-'+letter+'.olo.test:'+str(18443 if letter=='a' else 19443)
        clients.append((letter,Control(origin,required(own,'operator.token'),required(own,'worker/ca.crt'))))
    for letter,client in clients:
        # Retry startup GETs only; never replay an import write or uncertain effect.
        deadline=time.monotonic()+180
        while True:
            try:
                client.request('GET','/api/control/v1/admin-session')
                break
            except HttpRefused as failure:
                if failure.status not in (404,502,503,504) or time.monotonic()>=deadline:raise
            except Refused:
                if time.monotonic()>=deadline:raise
            time.sleep(1)
        result=deploy(client,raw)
        if result['state']!='PUBLISHED':raise ValueError('Tenant bot publication is not confirmed')
        print('Tenant '+letter.upper()+': selected bundle imported and published; execute a run to verify behavior.')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation',choices=['start','stop','status','validate','build-runtime'])
    parser.add_argument('--use-case',choices=['none',*IMPORT_CASES,'mcp-proxy'])
    parser.add_argument('--tenant',choices=['a','b','both'],default='both')
    parser.add_argument('--bot-file',type=Path,help='Actual exported .olo; never source credentials')
    args=parser.parse_args()
    use_case=args.use_case or (choose_use_case() if args.operation=='start' else 'none')
    if args.operation!='start' and (use_case!='none' or args.bot_file):parser.error('Use-case import is a start operation')
    if args.bot_file and use_case not in IMPORT_CASES:
        parser.error('--bot-file requires a bot import use case')
    if use_case in IMPORT_CASES and args.bot_file is None:
        if not sys.stdin.isatty():parser.error('Provide --bot-file for the selected use case')
        args.bot_file=Path(input('Path to the exported .olo: ').strip().strip('"'))
        args.tenant=input('Import into tenant a, b or both [both]: ').strip().lower() or 'both'
        if args.tenant not in ('a','b','both'):parser.error('Choose a, b or both')
    command(args.operation)
    if use_case in IMPORT_CASES:
        import_bot(args.bot_file,('a','b') if args.tenant=='both' else (args.tenant,),use_case)
    elif use_case=='mcp-proxy':
        print('MCP proxy setup: '+str(Path(__file__).resolve().parents[4]/'docs/gateway/mcp-proxy.md'))
        print('Register, scan, approve and grant the actual HTTPS upstream in the tenant console. No server is registered automatically.')


if __name__=='__main__':
    try:main()
    except Exception:
        print('Multitenant deployment refused. Check protected custody, image selection and operator instructions. No credentials or stores were converted or deleted.',file=sys.stderr)
        raise SystemExit(1)
