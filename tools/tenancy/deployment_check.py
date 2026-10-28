# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Check Compose ownership and real dedicated-pool mount/network boundaries using disposable fixtures."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import uuid

ROOT=Path(__file__).resolve().parents[2]
def docker(*args, **kwargs):
    return subprocess.run(['docker',*args],check=True,capture_output=True,text=True,**kwargs).stdout.strip()

def check(image):
    with tempfile.TemporaryDirectory() as temporary:
        path=Path(temporary);(path/'control.env').write_text('TOOLGATE_CONTROL_BUILDER_ENABLED=false\n')
        env=dict(os.environ)
        for key in ['CONTROL_IMAGE','GATEWAY_IMAGE','TOOL_HOST_IMAGE','INGRESS_IMAGE']:env[key]=image
        for key in ['CONTROL_CUSTODY','TENANT_A_GATEWAY_CUSTODY','TENANT_B_GATEWAY_CUSTODY','TENANT_A_HOST_CUSTODY','TENANT_B_HOST_CUSTODY','INGRESS_CUSTODY']:
            own=path/key;own.mkdir();env[key]=str(own)
        env.update(CONTROL_ENV=str(path/'control.env'),PLATFORM_NETWORK='operator-postgres')
        config=json.loads(docker('compose','-f',str(ROOT/'deploy/compose/Multitenant/compose.yaml'),'config','--format','json',env=env))
        services=config['services']
        assert services['control']['environment']['TOOLGATE_CONTROL_BUILDER_ENABLED']=='false'
        for owner in ['a','b']:
            host=services['host-'+owner];gateway=services['gateway-'+owner]
            assert set(host['networks'])=={'tenant-'+owner}
            assert set(gateway['networks'])=={'tenant-'+owner,'ingress-'+owner}
            for service in [host,gateway]:
                assert service['read_only'] and service['cap_drop']==['ALL'] and service['user']=='65532:65532'
                assert int(service['mem_limit'])>0 and float(service['cpus'])>0 and int(service['pids_limit'])>0
                assert not service.get('ports') and not service.get('privileged')
                assert all('CONTROL_CUSTODY' not in mount['source'] and 'docker.sock' not in mount['source'] for mount in service['volumes'])
        assert {v['source'] for v in services['host-a']['volumes']}.isdisjoint({v['source'] for v in services['host-b']['volumes']})
    names=[];containers=[];volumes=[]
    prefix='toolgate-pool-proof-'+uuid.uuid4().hex
    try:
        for owner in ['a','b']:
            network=prefix+'-'+owner;volume=network+'-data';names.append(network);volumes.append(volume)
            docker('network','create','--internal',network);docker('volume','create',volume)
            prepare="import os;open('/tenant/owner','w').write('"+owner+"');os.chown('/tenant',65532,65532);os.chmod('/tenant',0o700)"
            docker('run','--rm','--pull=never','--network','none','--user','0','-v',volume+':/tenant','--entrypoint','/usr/bin/python3',image,'-c',prepare)
            containers.append(docker('run','-d','--pull=never','--network',network,'--network-alias','host-'+owner,'--memory=64m','--cpus=1','--pids-limit=64','--read-only','--cap-drop=ALL','--security-opt=no-new-privileges','--user','65532:65532','-v',volume+':/tenant','--entrypoint','/usr/bin/python3',image,'-m','http.server','8000','--bind','0.0.0.0','--directory','/tenant'))
        for index,container in enumerate(containers):
            other=1-index;peer=json.loads(docker('inspect',containers[other]))[0]['NetworkSettings']['Networks'][names[other]]['IPAddress']
            probe="""import os,socket,urllib.request
assert open('/tenant/owner').read()==OWNER
assert not os.path.exists('/etc/toolgate') and not os.path.exists('/var/run/docker.sock')
try:socket.getaddrinfo(OTHER,8000);raise AssertionError('Foreign pool DNS visible')
except socket.gaierror:pass
try:
 urllib.request.build_opener(urllib.request.ProxyHandler({})).open('http://'+PEER+':8000',timeout=2)
 raise AssertionError('Foreign pool network reachable')
except (OSError,TimeoutError):pass
""".replace('OWNER',repr(['a','b'][index])).replace('OTHER',repr('host-'+['a','b'][other])).replace('PEER',repr(peer))
            docker('exec',container,'/usr/bin/python3','-c',probe)
        exhausted=subprocess.run(['docker','exec',containers[0],'/usr/bin/python3','-c','payload=bytearray(256*1024*1024)'],capture_output=True,timeout=20)
        assert exhausted.returncode!=0, 'Tenant A exceeded its enforced memory allocation'
        docker('exec',containers[1],'/usr/bin/python3','-c',"import urllib.request;assert urllib.request.urlopen('http://127.0.0.1:8000/owner',timeout=5).read()==b'b'")
        print('Compose ownership, private mounts/networks and tenant B availability during tenant A memory exhaustion passed.')
    finally:
        for container in containers:subprocess.run(['docker','rm','-f',container],capture_output=True)
        for name in names:subprocess.run(['docker','network','rm',name],capture_output=True)
        for volume in volumes:subprocess.run(['docker','volume','rm',volume],capture_output=True)

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--image',required=True);args=parser.parse_args();check(args.image)
