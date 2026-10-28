# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Read versioned installation configuration; authoring defaults are explicit, never a startup reset."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONFIGURATIONS = ROOT/'config/initial'
READ = ['read', 'list', 'info', 'search', 'events', 'hash', 'evaluate', 'transform', 'validate',
        'get', 'query', 'inspect', 'describe', 'status']
WRITE = READ + ['write', 'append', 'mkdir', 'copy', 'move', 'create', 'update', 'delete', 'remove', 'put', 'patch']
PERMISSION_CATALOG=json.loads((ROOT/'packages/contracts/management-permissions.json').read_text(encoding='utf-8'))
MANAGEMENT_PERMISSIONS=sorted(f"{resource['id']}.{action}" for resource in PERMISSION_CATALOG['resources'] for action in resource['actions'])
# Existing runtime tool verbs are distinct from namespaced console permissions.
MANAGE = ['read', 'create', 'update', 'delete', 'enable', 'disable', 'manage-role', 'grant', 'import',
          'export', 'audit', 'simulate', 'publish', 'policy', 'attest', 'rotate-credential',
          'approve-configuration', 'approve-operation', 'cancel-operation', 'approve-device',
          'revoke', 'build', 'deploy', 'vault-read', 'vault-write']
TIERS = ['ReadOnly', 'ReadAndWrite', 'Admin']


def catalog():
    records = {key: [] for key in ('teams', 'agentGroups', 'deviceGroups', 'toolGroups', 'roles', 'grants', 'policies', 'bindings')}
    conditions = dict(notBeforeUnixMs=0, expiresAtUnixMs=0, networkCidrs=[], devicePosture=[], regions=[],
                      hoursUtc=[], requireOnline=True, highRisk=False)
    selection = lambda ids: dict(ids=ids, all=False)
    resources = [dict(kind=kind, locator='', match='ANY') for kind in ('FILE', 'URL', 'DATABASE', 'CUSTOM')]
    for tier in TIERS:
        base = lambda id: dict(id=id, name=id, enabled=True, revision=1)
        human, actor, management = ['standard-'+tier+'-'+suffix for suffix in ('human', 'actor', 'management')]
        records['teams'].append(dict(**base(tier+'Team'), userIds=[], roleIds=[human, management]))
        records['agentGroups'].append(dict(**base(tier+'AgentGroup'), agentIds=[], roleIds=[actor]))
        records['deviceGroups'].append(dict(**base(tier+'DeviceGroup'), deviceIds=[]))
        records['toolGroups'].append(dict(**base(tier+'ToolGroup'), toolIds=[]))
        scope = dict(toolGroups=selection([t+'ToolGroup' for t in TIERS]),
                     deviceGroups=selection([t+'DeviceGroup' for t in TIERS]),
                     actions=[] if tier == 'Admin' else READ if tier == 'ReadOnly' else WRITE,
                     allActions=tier == 'Admin', resources=resources, conditions=conditions)
        for id, role_type in ((human, 'HUMAN'), (actor, 'ACTOR_SERVICE')):
            records['roles'].append(dict(**base(id), portalRole='BASIC', roleType=role_type, managementRules=[]))
        management_actions = [permission for permission in MANAGEMENT_PERMISSIONS if permission.endswith('.read') or permission=='simulation.run'] if tier == 'ReadOnly' else (
            [permission for permission in MANAGEMENT_PERMISSIONS if permission.rsplit('.',1)[1] in ('read','create','update','build','attest') or permission=='simulation.run'] if tier == 'ReadAndWrite' else MANAGEMENT_PERMISSIONS)
        rules = [dict(actions=management_actions, groupType=kind, groups=dict(ids=[], all=True),
                      grantableScopes=[scope] if tier == 'Admin' else [], conditions=conditions)
                 for kind in ('TEAM', 'AGENT_GROUP', 'TOOL_GROUP', 'DEVICE_GROUP')]
        records['roles'].append(dict(**base(management), portalRole='ADMINISTRATOR', roleType='MANAGEMENT', managementRules=rules))
        for purpose, source in (('HUMAN', human), ('CAPABILITY', actor), ('SERVICE', actor)):
            records['grants'].append(dict(**base('standard-'+tier+'-'+purpose.lower()), sourceType='ROLE',
                                         sourceId=source, purpose=purpose, scope=scope))
        records['policies'].append(dict(**base('standard-'+tier+'-access'), decision='ALLOW', scope=scope,
                                       teams=selection([tier+'Team']), agentGroups=selection([tier+'AgentGroup']),
                                       approverTeams=selection([])))
    # Destructive/system administration remains subject to runtime review, even with Admin grants.
    admin_scope = {**records['grants'][-1]['scope'], 'actions': ['execute', 'shell', 'install', 'deploy'], 'allActions': False}
    records['policies'].append(dict(id='standard-admin-sensitive-review', name='Review privileged operations',
        enabled=True, revision=1, decision='ASK', scope=admin_scope, teams=dict(ids=[], all=True),
        agentGroups=dict(ids=[], all=True), approverTeams=selection(['AdminTeam'])))
    for tool_level, tool_tier in enumerate(TIERS):
        for device_level, device_tier in enumerate(TIERS):
            level = min(tool_level, device_level)
            actions = READ if level == 0 else WRITE if level == 1 else sorted(set(WRITE+MANAGE+['execute', 'shell', 'install']))
            records['bindings'].append(dict(id='standard-'+tool_tier+'-'+device_tier,
                name=tool_tier+' tools on '+device_tier+' devices', enabled=True, revision=1,
                toolGroupId=tool_tier+'ToolGroup', deviceGroupId=device_tier+'DeviceGroup', actions=actions,
                allowedPackageDigests=[], requireOnline=True, ownerDependency=False))
    return dict(copyright='Copyright 2026 OLO Labs', license='Apache-2.0', version=1,
                readActions=READ, writeActions=WRITE, records=records)


def configuration_directory(release=None):
    release = release or (ROOT/'VERSION').read_text().strip()
    mapping=json.loads((CONFIGURATIONS/'releases.json').read_text())
    name=mapping['releases'][release]
    if not isinstance(name,str) or not name.replace('-','').isalnum():raise ValueError('Invalid configuration bundle name')
    return CONFIGURATIONS/name


def load(directory=None):
    directory=Path(directory) if directory else configuration_directory()
    manifest=json.loads((directory/'manifest.json').read_text())
    records={}
    for name in manifest['files']:
        if name not in ('groups.json','roles.json','grants.json','policies.json','bindings.json'):
            raise ValueError('Unsupported configuration file')
        for collection, values in json.loads((directory/name).read_text()).items():
            if collection not in ('teams','agentGroups','deviceGroups','toolGroups','roles','grants','policies','bindings'):
                raise ValueError('Initial presets must contain group-scoped records only')
            if collection in records:raise ValueError('Duplicate initial configuration collection')
            records[collection]=values
    return {**manifest,'records':records}


if __name__ == '__main__':
    import argparse
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write-defaults',type=Path,help='Author defaults in a NEW bundle directory')
    args=parser.parse_args()
    if args.write_defaults:
        directory=args.write_defaults
        if directory.exists():raise SystemExit('Use a new directory; existing editable configuration is never overwritten')
        directory.mkdir(parents=True)
        source=catalog();records=source.pop('records')
        source.update(bundleId=directory.name,files=['groups.json','roles.json','grants.json','policies.json','bindings.json'])
        write=lambda name,value:(directory/name).write_text(json.dumps(value,indent=2)+'\n',encoding='utf-8')
        write('manifest.json',source)
        write('groups.json',{k:records[k] for k in ('teams','agentGroups','deviceGroups','toolGroups')})
        for key in ('roles','grants','policies','bindings'):write(key+'.json',{key:records[key]})
    else:
        value=load();print('Configuration '+value['bundleId']+': '+str(sum(len(x) for x in value['records'].values()))+' records')
