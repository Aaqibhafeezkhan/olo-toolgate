# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Explicit administrator workflow transfer through current backend APIs only."""
import argparse
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import sys
import time
import secrets

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT))
from bot.bundle import MAX_BUNDLE,inventory,verify
from tools.admin.control import Control,Refused,read_file


def call(client,path,body=None,method=None,revision=None,raw=None):
    data=raw if raw is not None else json.dumps(body,separators=(',',':')).encode() if body is not None else None
    method=method or ('POST' if data is not None else 'GET')
    key='olo-'+hashlib.sha256(method.encode()+path.encode()+(data or b'')).hexdigest() if method!='GET' else None
    status,result=client.request(method,path,data,
        'application/vnd.ololabs.toolgate.package+zip' if raw is not None else 'application/merge-patch+json' if method=='PATCH' else 'application/json',key,revision)
    if status==202:raise Refused('Configuration review is pending; apply the reviewed change before retrying the import')
    return result


def merge_references(snapshot,references):
    result=deepcopy(snapshot)
    for owner in references.get('requiredIdentities',[]):
        if not any(user['id']==owner['id'] for user in result['requiredIdentities']):
            raise Refused('A referenced owner must be mapped to an existing enabled destination user')
    for collection,items in references.items():
        if collection in ('requiredIdentities','formatVersion','tenantId','revision'):continue
        if collection in ('users','identityBindings','deviceEvidence','devices','workloadBindings') and items:
            raise Refused('Human identities, device membership and workload credentials cannot be imported implicitly')
        if collection not in result:raise Refused('Unsupported referenced configuration collection')
        for item in items:
            if collection=='deviceGroups' and item.get('deviceIds'):
                raise Refused('Profile import cannot allocate device group membership')
            prior=next((row for row in result[collection] if row['id']==item['id']),None)
            if prior is None:result[collection].append(deepcopy(item));continue
            # Existing identity/membership allocations belong to this installation.
            candidate={**item,'revision':prior['revision']}
            for field in ('userIds','agentIds','toolIds','deviceIds','roleIds'):
                if field in prior:candidate[field]=sorted(set(prior[field])|set(candidate.get(field,[])))
            if collection in ('deviceGroups','agentGroups','toolGroups','teams'):
                if prior['enabled']!=candidate['enabled']:raise Refused('An existing group has conflicting enablement')
                candidate['name']=prior['name']
                if collection=='deviceGroups':candidate['deviceIds']=prior['deviceIds']
                result[collection][result[collection].index(prior)]=candidate
                continue
            if collection=='tools' and prior.get('version')==candidate.get('version') and prior.get('definition')==candidate.get('definition'):
                continue  # Keep the destination's qualified registration/runtime digest.
            if collection=='bindings':
                candidate['allowedPackageDigests']=prior['allowedPackageDigests']
            if prior!=candidate:
                # A bundle cannot silently overwrite a shared policy or definition.
                raise Refused('Referenced configuration conflicts with an existing definition: '+collection+'/'+item['id'])
    return result


def assignments(client):
    items=[];token=''
    for _ in range(11):
        page=call(client,'/api/control/v1/triggers/assignments'+('?pageToken='+token if token else ''))
        items.extend(page['items']);token=page.get('nextPageToken','')
        if not token:return items
    raise Refused('Assignment catalog exceeds import bounds')


def service_bindings(client,profile):
    # Destination-local verifier facts, never a credential transferred in .olo.
    # The runtime's authorized step remains the only holder of effect authority.
    rows=call(client,'/api/control/v1/workload-bindings?limit=100')['items']
    for agent in profile['workflows']['agentDefinitions']:
        matches=[row for row in rows if row['agentId']==agent['agentId'] and row['mode']=='SERVICE'
                 and row['enabled'] and row.get('parentBindingId') is None]
        if matches:
            if len(matches)!=1 or matches[0]['expiresAtUnixMs']<=int(time.time()*1000):
                raise Refused('Existing workflow service identity needs explicit repair')
            continue
        identity='workflow-'+hashlib.sha256(agent['agentId'].encode()).hexdigest()[:32]
        call(client,'/api/control/v1/workload-bindings',dict(id=identity,name='Workflow '+agent['agentId'],
            enabled=True,revision=1,agentId=agent['agentId'],mode='SERVICE',issuer='gateway',subject=identity,
            audience='gateway',credentialSha256=hashlib.sha256(secrets.token_bytes(32)).hexdigest(),
            credentialEpoch=1,expiresAtUnixMs=int(time.time()*1000)+86400000))


def deploy(client,bundle,*,timeout=360,after_references=None,after_provision=None):
    manifest=verify(bundle);files=inventory(bundle,MAX_BUNDLE,256*1024*1024)
    profile=json.loads(files['workflow.json']);references=json.loads(files['referenced-data.json'])
    snapshot=call(client,'/api/control/v1/config/export')
    for owner in references.get('requiredIdentities',[]):
        if not call(client,'/api/control/v1/users/'+owner['id'])['enabled']:
            raise Refused('A referenced destination owner is disabled')
    merged=merge_references(snapshot,references)
    call(client,'/api/control/v1/config/import',dict(snapshot=merged,mode='MERGE',dryRun=True),revision=snapshot['revision'])
    # All local bundle/identity/conflict checks precede server-side effects.
    for item in manifest['packages']:
        stored=call(client,'/api/control/v1/packages/uploads',raw=files[item['path']])
        if stored.get('state')!='VERIFIED' or any(stored.get(key)!=item[key] for key in ('packageId','version','packageDigest')):
            raise Refused('Control did not confirm the exact verified embedded package')
    # Confirmed package registration can update directory state. Merge against a
    # fresh observation, never retry a rejected/unknown configuration write.
    snapshot=call(client,'/api/control/v1/config/export')
    merged=merge_references(snapshot,references)
    if merged!=snapshot:
        call(client,'/api/control/v1/config/import',dict(snapshot=merged,mode='MERGE',dryRun=False),revision=snapshot['revision'])
    service_bindings(client,profile)
    if after_references is not None:after_references(profile)
    pending=deepcopy(profile);pending['workflows']['dispatchBindings']=[]
    base='/api/control/v1/workflow-profiles';identity=profile['profileId']
    def stage(document):
        value=call(client,base,document)
        call(client,base+'/'+identity+'/review',dict(profileDigest=value['profileDigest']))
        return value
    def provision(staged):
        deadline=time.monotonic()+timeout
        while True:
            state=call(client,base+'/'+identity+'/provision',dict(profileDigest=staged['profileDigest']))
            if state['state']=='READY':return
            if state['state']=='FAILED':raise Refused('Package preparation failed; inspect profile provisioning status')
            if time.monotonic()>deadline:raise Refused('Deployment remains pending; no workflow was activated; retry after fixing readiness')
            time.sleep(2)
    staged=stage(pending);provision(staged)
    if after_provision is not None:after_provision(profile)
    current=assignments(client)
    subscriptions=call(client,'/api/control/v1/triggers/subscriptions')['items']
    for connection in manifest['connections']:
        assignment=next((row for row in current if row['metadata']['name']==connection['assignment']),None)
        if assignment is None:raise Refused('Profile provisioning did not prepare the declared trigger assignment')
        subscription=next((row for row in subscriptions if row['metadata']['name']==connection['subscription']),None)
        if subscription is None:
            spec=deepcopy(connection['subscriptionSpec']);spec['expiresAtUnixMs']=min(spec['expiresAtUnixMs'],int(time.time()*1000)+30*86400000)
            call(client,'/api/control/v1/triggers/subscriptions',dict(name=connection['subscription'],spec=spec))
            subscriptions=call(client,'/api/control/v1/triggers/subscriptions')['items']
            subscription=next(row for row in subscriptions if row['metadata']['name']==connection['subscription'])
        if (subscription['spec']['filter']!=connection['subscriptionSpec']['filter']
                or subscription['spec']['sources']!=connection['subscriptionSpec']['sources']):
            raise Refused('An existing subscription has conflicting settings')
        binding=next(row for row in profile['workflows']['dispatchBindings'] if row['id']==connection['id'])
        binding.update(subscriptionUid=subscription['metadata']['uid'],sourceAssignmentUid=assignment['metadata']['uid'])
    staged=stage(profile);provision(staged)
    request=dict(profileDigest=staged['profileDigest'],expectedGeneration=staged['generation'])
    call(client,base+'/'+identity+'/graphs:prepare',request)
    status=call(client,base+'/'+identity+'/revisions/'+staged['profileDigest'])
    if not status['dependenciesReady']:raise Refused('Runtime/model/device dependencies are not ready; the previous publication is preserved')
    published=call(client,base+'/'+identity+'/publish',request)
    return dict(profileId=identity,profileDigest=published['profileDigest'],generation=published['generation'],state=published['state'])


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('bundle',type=Path)
    parser.add_argument('--origin',required=True)
    parser.add_argument('--ca-file',type=Path)
    parser.add_argument('--token-file',required=True,type=Path)
    args=parser.parse_args()
    if args.bundle.suffix!='.olo':parser.error('Use a current .olo workflow bundle')
    client=Control(args.origin,args.token_file,args.ca_file)
    print(json.dumps(deploy(client,read_file(args.bundle,MAX_BUNDLE))))


if __name__=='__main__':
    try:main()
    except (ValueError,OSError,KeyError):
        print('Workflow import stopped before activation or its outcome is unknown. Inspect Control status; no automatic effect retry.',file=sys.stderr)
        raise SystemExit(1)
