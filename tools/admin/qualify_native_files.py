# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Explicit installation decision for a vetted native filesystem owner; never profile authority."""
import argparse
import json
from pathlib import Path
import sys
import time

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT))
from tools.admin.control import Control,Refused
from tools.admin.import_workflow import call
from tools.admin.provision_devices import identifier


def qualify(client,device,group,tools,days=1):
    identifier(device);identifier(group)
    if type(days) is not int or not 1<=days<=7:raise Refused('Qualification duration must be one to seven days')
    candidates=call(client,'/api/control/v1/mcp/native-files/candidates/'+device)['items']
    selected=[row for row in candidates if row['toolId'] in set(tools)]
    if not tools or {row['toolId'] for row in selected}!=set(tools):raise Refused('Every selected tool must have an installed file-only candidate')
    images=sorted({row['imageDigest'] for row in selected})
    expires=int(time.time()*1000)+days*86400000
    for image in images:
        call(client,'/api/control/v1/mcp/native-files/qualifications',dict(deviceId=device,deviceGroupId=group,
            imageDigest=image,expiresAtUnixMs=expires,acknowledgedRisk='UNFENCED_FILESYSTEM'))
    return dict(deviceId=device,deviceGroupId=group,imageDigests=images,expiresAtUnixMs=expires)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--origin',required=True);parser.add_argument('--token-file',type=Path,required=True)
    parser.add_argument('--ca-file',type=Path);parser.add_argument('--device',required=True);parser.add_argument('--group',required=True)
    parser.add_argument('--tool',action='append',required=True);parser.add_argument('--days',type=int,default=1)
    parser.add_argument('--acknowledge-unfenced-files',action='store_true',required=True,
                        help='Operator has vetted the protected Linux owner/confinement and accepts unfenced filesystem effects')
    args=parser.parse_args()
    print(json.dumps(qualify(Control(args.origin,args.token_file,args.ca_file),args.device,args.group,args.tool,args.days)))


if __name__=='__main__':
    try:main()
    except (Refused,OSError):
        print('Qualification refused; inspect current authority, installed image candidates and group membership',file=sys.stderr)
        raise SystemExit(1)
