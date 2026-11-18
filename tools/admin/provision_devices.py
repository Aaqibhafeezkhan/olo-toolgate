# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Configure Admin device policy and explicitly allocate connected devices using backend APIs."""
import argparse
from pathlib import Path
import sys
import time
import re

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT))
from tools.admin.control import Control,Refused,HttpRefused
from tools.admin.import_workflow import call


def identifier(value):
    if not isinstance(value,str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._:-]{0,127}',value):
        raise Refused('Use an explicit device, owner or group identifier')
    return value


def approval_policy(client,owner='admin',days=30):
    identifier(owner)
    if type(days) is not int or not 1<=days<=3650:raise Refused('Approval duration must be between 1 and 3650 days')
    user=call(client,'/api/control/v1/users/'+owner)
    if not user['enabled']:raise Refused('Auto-approval owner is not enabled')
    current=call(client,'/api/control/v1/settings?section=device')
    requested=dict(autoApprove=True,durationMs=days*86400000,ownerUserId=owner)
    if current.get('deviceApproval')==requested:return current
    return call(client,'/api/control/v1/settings',dict(deviceApproval=requested),'PATCH',revision=current['revision'])


def allocate(client,device,groups):
    identifier(device)
    groups=sorted(set(identifier(group) for group in groups))
    if not groups:raise Refused('Device allocation requires an explicit destination group')
    endpoint=call(client,'/api/control/v1/endpoint/devices/'+device)
    if endpoint['state']!='ACTIVE' or endpoint.get('connectionApproved') is False:
        raise Refused('Device is not approved and active under the configured approval policy')
    if endpoint.get('connectionExpiresAtUnixMs',int(time.time()*1000)+1)<=int(time.time()*1000):
        raise Refused('Device approval expired')
    for group in groups:
        if not call(client,'/api/control/v1/device-groups/'+group)['enabled']:
            raise Refused('Destination device group is disabled')
    record=call(client,'/api/control/v1/devices/'+device)
    if not record['enabled']:
        call(client,'/api/control/v1/endpoint/devices/'+device+'/enabled',dict(expectedRevision=record['revision'],enabled=True))
    current=call(client,'/api/control/v1/devices/'+device+'/groups')
    target=sorted(set(current['groupIds'])|set(groups))
    if target!=sorted(current['groupIds']):
        call(client,'/api/control/v1/devices/'+device+'/groups',{**current,'groupIds':target},'PUT',revision=current['revision'])
    return dict(deviceId=device,groupIds=target)


def approve_pending(client,device,days):
    """Finish an existing enrollment only for the operator's explicitly selected ID."""
    identifier(device)
    if type(days) is not int or not 1<=days<=3650:raise Refused('Approval duration must be between 1 and 3650 days')
    rows=call(client,'/api/control/v1/endpoint/devices')['items']
    selected=[row for row in rows if row['deviceId']==device]
    if len(selected)>1:raise Refused('Ambiguous enrolled device identity')
    enrollment=selected[0].get('enrollment') if selected else None
    if not enrollment:return
    if enrollment['state']!='PENDING':raise Refused('Enrollment is not pending')
    code=enrollment.get('userCode');fingerprint=enrollment.get('keyFingerprint')
    if not code or not re.fullmatch(r'[A-Za-z0-9]+',code) or not fingerprint:
        raise Refused('Current authority cannot review this enrollment')
    review=call(client,'/api/control/v1/endpoint/enrollments/review?code='+code)
    if (review['deviceId'],review.get('keyFingerprint'),review['state'])!=(device,fingerprint,'PENDING'):
        raise Refused('Enrollment identity changed; no approval was sent')
    call(client,'/api/control/v1/endpoint/enrollments/decision',dict(userCode=code,keyFingerprint=fingerprint,
        choice='APPROVE',connectionExpiresAtUnixMs=int(time.time()*1000)+days*86400000))


def wait_active(client,device,seconds):
    """Poll observations only; never repeat approval or membership mutations."""
    identifier(device)
    if type(seconds) is not int or not 0<=seconds<=3600:raise Refused('Wait duration must be between 0 and 3600 seconds')
    deadline=time.monotonic()+seconds
    while True:
        try:endpoint=call(client,'/api/control/v1/endpoint/devices/'+device)
        except HttpRefused as error:
            if error.status!=404:raise
            endpoint={'state':'PENDING'}
        if endpoint['state']=='ACTIVE':return endpoint
        if endpoint['state']=='REVOKED':raise Refused('Device was revoked')
        if time.monotonic()>=deadline:raise Refused('Device is not active; approval policy remains configured; no allocation was attempted')
        time.sleep(min(2,max(0,deadline-time.monotonic())))


def main():
    import json
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--origin',required=True);parser.add_argument('--token-file',type=Path,required=True)
    parser.add_argument('--ca-file',type=Path);parser.add_argument('--owner',default='admin')
    parser.add_argument('--days',type=int,default=30);parser.add_argument('--device',action='append',default=[])
    parser.add_argument('--group',action='append',default=[])
    parser.add_argument('--wait-seconds',type=int,default=180,help='Wait for each explicit enrolled device to become ACTIVE before allocation')
    args=parser.parse_args()
    if args.device and not args.group:parser.error('Explicit device allocation requires at least one --group')
    # Validate the whole requested allocation before enabling tenant-wide approval.
    for value in [args.owner,*args.device,*args.group]:identifier(value)
    if not 0<=args.wait_seconds<=3600:parser.error('--wait-seconds must be between 0 and 3600')
    client=Control(args.origin,args.token_file,args.ca_file)
    policy=approval_policy(client,args.owner,args.days)
    allocated=[]
    for device in args.device:
        approve_pending(client,device,args.days)
        wait_active(client,device,args.wait_seconds)
        allocated.append(allocate(client,device,args.group))
    print(json.dumps(dict(autoApprove=policy['deviceApproval']['autoApprove'],devices=allocated)))


if __name__=='__main__':
    try:main()
    except (Refused,OSError,KeyError,TypeError) as error:
        print(str(error) if isinstance(error,Refused) else 'Provisioning failed; inspect current device policy and membership before retrying',file=sys.stderr)
        raise SystemExit(1)
