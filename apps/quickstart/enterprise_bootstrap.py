# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Installation-only group graph. No runtime grants, legacy policy or HTTP admin bypass."""
import base64
import hashlib
import json
import os
import re
import time
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, ec


def prepare(data, tenant, issuer, key, atomic, profiles):
    state = data / 'state/native'
    state.mkdir(mode=0o700, exist_ok=True)
    device_key = state / 'device-key'
    if not device_key.exists():
        private = ec.generate_private_key(ec.SECP256R1())
        atomic(device_key, private.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                                serialization.NoEncryption()))
    private = serialization.load_pem_private_key(device_key.read_bytes(), password=None)
    device = 'device-' + hashlib.sha256(private.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).hexdigest()[:32]
    packet_path = data / 'installation-review.json'
    trust_path = data / 'installation-trust.json'
    # Keep the original signed installation packet. Restart cannot recreate it or restore permissions.
    if packet_path.exists():
        return device, packet_path, trust_path
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
    actions = ['read','create','update','delete','enable','disable','recover','manage-role','grant','import','export',
               'audit','simulate','publish','policy','attest','rotate-credential','approve-configuration',
               'approve-operation','cancel-operation','approve-device','revoke','build','deploy','vault-read','vault-write']
    snapshot['roles'] = [dict(id='installation-management', name='Super Admin', enabled=True,
                             revision=1, portalRole='SUPER_ADMIN', roleType='MANAGEMENT',
                             managementRules=[dict(actions=actions, groupType=kind, groups=all_groups,
                                                   grantableScopes=[ceiling], conditions=conditions)
                                              for kind in ['TEAM','AGENT_GROUP','TOOL_GROUP','DEVICE_GROUP']])]
    snapshot['identityBindings'] = [dict(id='installed-'+user, name='Reviewed local identity', enabled=True,
                                       revision=1, userId=user, issuer=issuer, subject=user, sessionEpoch=1,
                                       firstSeenUnixMs=now, lastAttemptUnixMs=now, attemptCount=1,
                                       registrationReason='REVIEWED_INSTALLATION', sessionsValidAfterUnixMs=0)
                                   for user in subjects]
    snapshot['agents'] = [dict(id='agent-local', name='Local workload awaiting configuration', enabled=False,
                              revision=1, ownerUserId='admin')]
    snapshot['agentGroups'] = [dict(id='default-agents', name='Default agent group', enabled=True,
                                   revision=1, agentIds=['agent-local'], roleIds=[])]
    snapshot['deviceGroups'] = [dict(id='default-devices', name='Default device group', enabled=True,
                                    revision=1, deviceIds=[])]
    for profile in profiles:
        snapshot['tools'].append({**profile['tool'], 'enabled': False})
        snapshot['extractors'].append(profile['extractor'])
    snapshot['toolGroups'] = [dict(id='default-tools', name='Default tool group', enabled=True, revision=1,
                                  toolIds=[tool['id'] for tool in snapshot['tools']])]
    snapshot['bindings'] = [dict(id='binding-default', name='Reviewed default execution boundary', enabled=True,
                                revision=1, toolGroupId='default-tools', deviceGroupId='default-devices',
                                actions=sorted({action['name'] for tool in snapshot['tools']
                                                for action in tool['definition']['actions']}),
                                allowedPackageDigests=sorted({tool['packageDigest'] for tool in snapshot['tools']}),
                                requireOnline=True, ownerDependency=False)]
    authorization = dict(formatVersion=1, tenantId=tenant, expectedRevision=0, snapshot=snapshot,
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
    return device, packet_path, trust_path
