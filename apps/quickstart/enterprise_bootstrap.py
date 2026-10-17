# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Installation-only group graph, plus an optional reviewed initialize file. No HTTP admin bypass."""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import time
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, ec

COLLECTIONS = ('users', 'teams', 'agents', 'tools', 'policies', 'devices', 'roles', 'deviceGroups', 'agentGroups',
               'toolGroups', 'grants', 'delegations', 'agentDelegations', 'bindings', 'extractors', 'workloadBindings',
               'identityBindings', 'deviceEvidence')
# File-level fields read only here; they never become directory records.
FILE_FIELDS = {'formatVersion', 'tenantId', 'revision', 'settings', 'requiredIdentities', 'visitorPool', 'reservedRecords'}
TOOL_HOST = 'tool-host-local'  # Enrolls on first start; its device record counts against the directory.
PER_ACCOUNT = 5  # Pool account: user, agent, workload binding, identity binding and one owned device.
YEAR_MS = 365 * 86400000
LOCAL_ID = r'[A-Za-z0-9][A-Za-z0-9._-]{0,127}'


class Refused(ValueError):
    """A startup refusal whose message names the fix; it never carries a secret."""


def strict(raw):
    def unique(pairs):
        value = {}
        for name, item in pairs:
            if name in value: raise ValueError('Duplicate field')
            value[name] = item
        return value
    return json.loads(raw, object_pairs_hook=unique, parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))


def number(value, low, high):
    return isinstance(value, int) and not isinstance(value, bool) and low <= value <= high


def read_initialize():
    """TOOLGATE_INITIALIZE_FILE: one configuration snapshot (or import envelope) applied on a fresh install."""
    name = os.environ.get('TOOLGATE_INITIALIZE_FILE', '').strip()
    if not name: return {}
    path = Path(name)
    if not path.is_file() or path.stat().st_size > 2*1024*1024:
        raise Refused('TOOLGATE_INITIALIZE_FILE must name a readable file of at most 2 MiB')
    try: document = strict(path.read_bytes())
    except ValueError: raise Refused('TOOLGATE_INITIALIZE_FILE is not valid JSON') from None
    if isinstance(document, dict) and isinstance(document.get('snapshot'), dict): document = document['snapshot']
    if not isinstance(document, dict) or document.get('formatVersion') != 2 or set(document)-FILE_FIELDS-set(COLLECTIONS):
        raise Refused('TOOLGATE_INITIALIZE_FILE must be a formatVersion 2 configuration snapshot')
    for name in COLLECTIONS:
        rows = document.get(name, [])
        if not isinstance(rows, list) or any(not isinstance(row, dict) or not isinstance(row.get('id'), str) for row in rows) \
                or len({row['id'] for row in rows}) != len(rows):
            raise Refused('Invalid or duplicate '+name+' in the initialize file')
    if not number(document.get('reservedRecords', 0), 0, 100000): raise Refused('Invalid reservedRecords in the initialize file')
    return document


def environment(settings):
    """Server settings reach Control as its startup variables; the supervisor lets container values win."""
    if settings is None: return {}
    if not isinstance(settings, dict) or any(not isinstance(settings.get(name, {}), dict) for name in settings):
        raise Refused('Invalid settings in the initialize file')
    gateway, approval, review = (settings.get(name, {}) for name in ('gateway', 'deviceApproval', 'configuration'))
    values = {}
    if isinstance(gateway.get('gatewayName'), str): values['TOOLGATE_GATEWAY_NAME'] = gateway['gatewayName']
    if isinstance(approval.get('autoApprove'), bool):
        values['TOOLGATE_SETTINGS_AUTO_APPROVE_DEVICES'] = 'true' if approval['autoApprove'] else 'false'
    if number(approval.get('durationMs'), 1, 9007199254740991):
        values['TOOLGATE_SETTINGS_AUTO_APPROVE_DURATION_DAYS'] = str(max(1, approval['durationMs']//86400000))
    if isinstance(approval.get('ownerUserId'), str): values['TOOLGATE_SETTINGS_AUTO_APPROVE_OWNER'] = approval['ownerUserId']
    if number(review.get('requiredReviews'), 0, 2): values['TOOLGATE_CONFIG_REQUIRED_REVIEWS'] = str(review['requiredReviews'])
    return values


def installed_identity(user, issuer, now):
    return dict(id='installed-'+user, name='Reviewed local identity', enabled=True, revision=1, userId=user,
                issuer=issuer, subject=user, sessionEpoch=1, firstSeenUnixMs=now, lastAttemptUnixMs=now, attemptCount=1,
                registrationReason='REVIEWED_INSTALLATION', sessionsValidAfterUnixMs=0)


def credential(binding, digest, now):
    """A workload binding carrying a new credential hash; Gateway facts default to the static-token form."""
    if binding.get('mode') not in ('SERVICE', 'DELEGATED'): raise Refused('Workload binding '+binding['id']+' needs mode SERVICE or DELEGATED')
    binding = dict(binding, credentialSha256=digest)
    binding.setdefault('issuer', 'gateway'); binding.setdefault('subject', binding['id']); binding.setdefault('audience', 'gateway')
    if not number(binding.get('credentialEpoch'), 1, 9007199254740991): binding['credentialEpoch'] = 1
    if not number(binding.get('expiresAtUnixMs'), now+1, 9007199254740991): binding['expiresAtUnixMs'] = now+YEAR_MS
    if binding['mode'] == 'DELEGATED': binding['delegatedSessionEpoch'] = 1  # The installed identity's first epoch.
    return binding


def execution_binding(snapshot, agent):
    """The execution binding an agent's calls are placed through: the first one its capability grants cover."""
    groups = {group['id'] for group in snapshot.get('agentGroups', []) if agent in group.get('agentIds', [])}
    selected = lambda selection, value: isinstance(selection, dict) and (selection.get('all') is True or value in selection.get('ids', []))
    scopes = [grant.get('scope', {}) for grant in snapshot.get('grants', []) if grant.get('purpose') == 'CAPABILITY'
              and grant.get('sourceType') == 'AGENT_GROUP' and grant.get('sourceId') in groups and grant.get('enabled', True)]
    for binding in sorted(snapshot.get('bindings', []), key=lambda row: row['id']):
        if binding.get('enabled', True) and any(selected(scope.get('toolGroups'), binding.get('toolGroupId'))
                                                and selected(scope.get('deviceGroups'), binding.get('deviceGroupId')) for scope in scopes):
            return binding['id']
    return None


def workload_context(tenant, binding, execution):
    """The Gateway RequestContext for a static workload token. It names no device."""
    context = dict(requestId=binding['id'], tenantId=tenant, mode=binding['mode'], credentialKind='STATIC_TOKEN',
                   agentId=binding['agentId'], workloadBindingId=binding['id'], chain=[],
                   credentialEpoch=binding['credentialEpoch'], credentialSha256=binding['credentialSha256'])
    if binding['mode'] == 'DELEGATED': context.update(userId=binding['delegatedUserId'], sessionEpoch=binding['delegatedSessionEpoch'])
    if execution: context['bindingId'] = execution
    return context


def initialize(snapshot, tenant, issuer, now):
    """Merge the initialize file and its visitor pool into the installation snapshot.

    Returns local installation state: file logins, agent tokens and contexts, pool accounts, settings variables
    and the record budget. Pool accounts start disabled and without any usable credential.
    """
    document = read_initialize()
    values = environment(document.get('settings'))
    installed_users={user['id'] for user in snapshot['users']}
    for name in COLLECTIONS:
        rows = {row['id']: row for row in snapshot[name]}
        for row in document.get(name, []):
            row = dict(row)
            if name == 'teams': row['userIds'] = sorted(set(rows.get(row['id'], {}).get('userIds', [])) | set(row.get('userIds', [])))
            rows[row['id']] = row
        snapshot[name] = list(rows.values())
    # A tool, device or agent belongs to exactly one group: the file's group takes it from the installation's group.
    for name, members in (('toolGroups', 'toolIds'), ('deviceGroups', 'deviceIds'), ('agentGroups', 'agentIds')):
        claimed = {member for row in document.get(name, []) for member in row.get(members, [])}
        listed = {row['id'] for row in document.get(name, [])}
        for index, row in enumerate(snapshot[name]):
            if row['id'] not in listed and claimed & set(row.get(members, [])):
                snapshot[name][index] = {**row, members: [member for member in row[members] if member not in claimed]}
    users = {user['id'] for user in snapshot['users']}
    teams = {team['id']: team for team in snapshot['teams']}
    # Initialize resolves every required identity as existing or as a disabled placeholder, added to its teams.
    for required in document.get('requiredIdentities', []):
        if not isinstance(required, dict) or not isinstance(required.get('id'), str) or not isinstance(required.get('teamIds', []), list):
            raise Refused('Invalid requiredIdentities in the initialize file')
        if required['id'] not in users:
            snapshot['users'].append(dict(id=required['id'], name=required['id'], enabled=False, revision=1)); users.add(required['id'])
        for team in required.get('teamIds', []):
            if team in teams and required['id'] not in teams[team]['userIds']: teams[team]['userIds'].append(required['id'])
    identities = {binding['id'] for binding in snapshot['identityBindings']}
    logins = []
    for user in document.get('users', []):
        if user['id'] in installed_users or user.get('enabled') is not True: continue
        if not re.fullmatch(LOCAL_ID, user['id']): raise Refused('User id '+user['id']+' cannot be a local login')
        if 'installed-'+user['id'] not in identities: snapshot['identityBindings'].append(installed_identity(user['id'], issuer, now))
        logins.append(dict(id=user['id'], showOnLogin=user.get('showOnLogin') is True))
    agents = []
    workloads = {row['id'] for row in document.get('workloadBindings', [])}
    for index, binding in enumerate(snapshot['workloadBindings']):
        if binding['id'] not in workloads: continue
        token = secrets.token_urlsafe(48)
        snapshot['workloadBindings'][index] = credential(binding, hashlib.sha256(token.encode()).hexdigest(), now)
        agents.append(dict(agentId=binding['agentId'], workloadBindingId=binding['id'], userId=binding.get('delegatedUserId'), token=token))
    # Budget: every fixed record, the Tool Host, the file's reservation and five records per pool account.
    devices = {device['id'] for device in snapshot['devices']}
    fixed = sum(len(snapshot[name]) for name in COLLECTIONS)+(TOOL_HOST not in devices)+document.get('reservedRecords', 0)
    limit = os.environ.get('TOOLGATE_CONTROL_MAX_RECORDS', '').strip() or '512'
    if not limit.isdigit(): raise Refused('TOOLGATE_CONTROL_MAX_RECORDS must be a number')
    largest = max(0, (int(limit)-fixed)//PER_ACCOUNT)
    if fixed > int(limit): raise Refused('The initialize file needs '+str(fixed)+' directory records; Control holds at most '+limit)
    accounts = []
    pool = document.get('visitorPool')
    if pool is not None:
        size = os.environ.get('TOOLGATE_DEMO_ACCOUNT_POOL', '').strip() or '50'
        if not size.isdigit() or int(size) > 999: raise Refused('TOOLGATE_DEMO_ACCOUNT_POOL must be a number from 0 to 999')
        if int(size) > largest:
            raise Refused('TOOLGATE_DEMO_ACCOUNT_POOL='+size+' needs '+str(fixed+PER_ACCOUNT*int(size))+' of '+limit
                          +' directory records; the largest pool that fits is '+str(largest))
        groups = {group['id']: group for group in snapshot['agentGroups']}
        claims, passed = pool.get('claims', {}), pool.get('passClaims')
        if not isinstance(pool.get('prefix'), str) or pool.get('teamId') not in teams or pool.get('agentGroupId') not in groups \
                or 'delegationId' in pool and pool['delegationId'] not in {row['id'] for row in snapshot['delegations']} \
                or not isinstance(claims, dict) or any(not isinstance(v, list) or not v or not all(isinstance(i, str) for i in v) for v in claims.values()) \
                or passed is not None and not isinstance(passed, dict):
            raise Refused('Invalid visitorPool in the initialize file')
        taken = {row['id'] for name in COLLECTIONS for row in snapshot[name]}
        team, group = teams[pool['teamId']], groups[pool['agentGroupId']]
        for n in range(1, int(size)+1):
            user = pool['prefix']+format(n, '03d'); agent = user+'-agent'; workload = user+'-workload'
            if not re.fullmatch(LOCAL_ID, user) or {user, agent, workload, 'installed-'+user} & taken: raise Refused('visitorPool ids collide or are invalid: '+user)
            record = dict(id=user, name=user, enabled=False, revision=1)
            if claims: record['claims'] = {name: values[(n-1) % len(values)] for name, values in claims.items()}
            snapshot['users'].append(record); team['userIds'].append(user)
            snapshot['agents'].append(dict(id=agent, name=user+' agent', enabled=True, revision=1, ownerUserId=user))
            group['agentIds'] = [*group.get('agentIds', []), agent]
            # Disabled, with a hash nobody holds: the first claim rotates it to a real token and enables it.
            binding = dict(id=workload, name=user+' delegated agent', enabled=False, revision=1, agentId=agent, mode='DELEGATED', delegatedUserId=user)
            if passed is not None: binding['passClaims'] = passed
            snapshot['workloadBindings'].append(credential(binding, hashlib.sha256(secrets.token_bytes(32)).hexdigest(), now))
            snapshot['identityBindings'].append(installed_identity(user, issuer, now))
            accounts.append(dict(userId=user, agentId=agent, workloadBindingId=workload))
    by_id = {binding['id']: binding for binding in snapshot['workloadBindings']}
    for entry in agents+accounts:
        binding = by_id[entry['workloadBindingId']]
        entry['context'] = workload_context(tenant, binding, execution_binding(snapshot, binding['agentId']))
    return dict(users=logins, agents=agents, pool=accounts, environment=values, maxPool=largest, fixedCredentials=1+len(agents))


def prepare(data, tenant, issuer, key, atomic):
    packet_path = data / 'installation-review.json'
    trust_path = data / 'installation-trust.json'
    # Keep the original signed installation packet. Restart cannot recreate it or restore permissions.
    if packet_path.exists():
        return packet_path, trust_path
    if (data / 'state/control.sqlite').exists():
        raise ValueError('Existing state requires externally reviewed recovery; automatic privilege conversion is unavailable')
    admin_email = os.environ.get('TOOLGATE_ADMIN_EMAIL', 'admin@admin.com').strip()
    if len(admin_email) > 254 or not re.fullmatch(r'[^\s@]+@[^\s@]+\.[^\s@]+', admin_email):
        raise ValueError('TOOLGATE_ADMIN_EMAIL must be a valid email address')
    now = int(time.time() * 1000)
    # Workload bindings require a later reviewed configuration, even when disabled.
    snapshot = dict(formatVersion=2, tenantId=tenant, revision=0, users=[], teams=[], agents=[], tools=[],
                    policies=[], devices=[], roles=[], deviceGroups=[], agentGroups=[], toolGroups=[], grants=[],
                    delegations=[], agentDelegations=[], bindings=[], extractors=[], workloadBindings=[],
                    identityBindings=[], deviceEvidence=[])
    administrators = ['admin', 'reviewer-1', 'reviewer-2']
    subjects = administrators + ['test-readonly', 'test-readwrite', 'test-admin']
    snapshot['users'] = [dict(id=user, name='Quickstart '+user, enabled=user in administrators, revision=1) for user in subjects]
    snapshot['users'][0]['email'] = admin_email
    snapshot['teams'] = [dict(id='team-default', name='Default team', enabled=True, revision=1,
                              userIds=subjects, roleIds=[]),
                         dict(id='installation-administrators', name='Installation administrators', enabled=True,
                              revision=1, userIds=administrators, roleIds=['installation-management'])]
    conditions = dict(notBeforeUnixMs=0, expiresAtUnixMs=0, networkCidrs=[], devicePosture=[], regions=[],
                      hoursUtc=[], requireOnline=True, highRisk=False)
    all_groups = dict(ids=[], all=True)
    ceiling = dict(toolGroups=all_groups, deviceGroups=all_groups, actions=[], allActions=True,
                   resources=[dict(kind=kind, locator='', match='ANY') for kind in ['FILE','URL','DATABASE','CUSTOM']],
                   conditions=conditions)
    catalog_path=Path(__file__).with_name('management-permissions.json')
    if not catalog_path.is_file():catalog_path=Path(__file__).resolve().parents[2]/'packages/contracts/management-permissions.json'
    catalog=json.loads(catalog_path.read_text(encoding='utf-8'))
    actions=sorted(f"{resource['id']}.{action}" for resource in catalog['resources'] for action in resource['actions'])
    snapshot['roles'] = [dict(id='installation-management', name='Super Admin', enabled=True,
                             revision=1, portalRole='SUPER_ADMIN', roleType='MANAGEMENT',
                             managementRules=[dict(actions=actions, groupType=kind, groups=all_groups,
                                                   grantableScopes=[ceiling], conditions=conditions)
                                              for kind in ['TEAM','AGENT_GROUP','TOOL_GROUP','DEVICE_GROUP']])]
    snapshot['identityBindings'] = [installed_identity(user, issuer, now) for user in subjects]
    snapshot['agents'] = [dict(id='agent-local', name='Local workload awaiting configuration', enabled=False,
                              revision=1, ownerUserId='admin')]
    snapshot['agentGroups'] = [dict(id='default-agents', name='Default agent group', enabled=True,
                                   revision=1, agentIds=['agent-local'], roleIds=[])]
    snapshot['deviceGroups'] = [dict(id='default-devices', name='Default device group', enabled=True,
                                    revision=1, deviceIds=[])]
    snapshot['toolGroups'] = [dict(id='default-tools', name='Default tool group', enabled=True, revision=1,
                                  toolIds=[tool['id'] for tool in snapshot['tools']])]
    snapshot['bindings'] = [dict(id='binding-default', name='Reviewed default execution boundary', enabled=True,
                                revision=1, toolGroupId='default-tools', deviceGroupId='default-devices',
                                actions=['invoke'], allowedPackageDigests=[],
                                requireOnline=True, ownerDependency=False)]
    installation = json.loads(json.dumps(snapshot))
    installed = initialize(snapshot, tenant, issuer, now)
    # The packet carries only installation records; Control applies the file's records as its own first import.
    extra = {name: [row for row in snapshot[name] if row not in installation[name]] for name in COLLECTIONS}
    if any(extra.values()):
        atomic(data / 'state/initialize-snapshot.json', json.dumps(dict(formatVersion=2, tenantId=tenant, revision=0, **extra)))
        (data / 'state/initialize-snapshot.json').chmod(0o600)
    # Tokens, logins and settings land before the packet, which is the commit point of a fresh install.
    atomic(data / 'state/installation.json', json.dumps(installed))
    (data / 'state/installation.json').chmod(0o600)
    authorization = dict(formatVersion=1, tenantId=tenant, expectedRevision=0, snapshot=installation,
                         reasonDigest=hashlib.sha256(b'Fresh Quickstart installation; zero runtime grants').hexdigest(),
                         issuedAtUnixMs=now, expiresAtUnixMs=now+3600000)
    installation_key = key('installation')
    encode = lambda raw: base64.urlsafe_b64encode(raw).rstrip(b'=').decode()
    message = b'OLO ToolGate recovery v1\n'+json.dumps(authorization, sort_keys=True,
                                                       separators=(',', ':'), ensure_ascii=False).encode()
    proof = installation_key.sign(message, padding.PKCS1v15(), hashes.SHA256())
    numbers = installation_key.public_key().public_numbers()
    number = lambda value: encode(value.to_bytes((value.bit_length()+7)//8, 'big'))
    atomic(trust_path, json.dumps(dict(keys=[dict(kid='installation-local', n=number(numbers.n), e=number(numbers.e))])))
    atomic(packet_path, json.dumps(dict(authorization=authorization,
                                      proofs=[dict(keyId='installation-local', signature=encode(proof))])))
    return packet_path, trust_path
