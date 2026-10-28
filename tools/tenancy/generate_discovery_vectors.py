# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Generate public-only cross-language discovery vectors; private keys never leave memory."""
import base64
from copy import deepcopy
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.x509.oid import NameOID

ROOT = Path(__file__).resolve().parents[2]
START = 1700000000000
TENANT = '10000000-0000-4000-8000-000000000001'
OTHER = '10000000-0000-4000-8000-000000000002'


def encode(raw): return base64.urlsafe_b64encode(raw).rstrip(b'=').decode()
def number(value): return encode(value.to_bytes((value.bit_length()+7)//8, 'big'))
def document(value): return json.dumps(value, sort_keys=True, separators=(',', ':')).encode()
def key(): return rsa.generate_private_key(public_exponent=65537, key_size=3072)


def jwk(kid, private):
    public = private.public_key().public_numbers()
    return dict(kty='RSA', use='sig', alg='RS256', kid=kid, n=number(public.n), e=number(public.e))


def authority(tenant):
    private = key()
    subject = x509.Name([x509.NameAttribute(NameOID.ORGANIZATION_NAME, 'ToolGate discovery test'),
                         x509.NameAttribute(NameOID.ORGANIZATIONAL_UNIT_NAME, tenant),
                         x509.NameAttribute(NameOID.COMMON_NAME, 'Tenant device identity authority')])
    cert = (x509.CertificateBuilder().subject_name(subject).issuer_name(subject)
            .public_key(private.public_key()).serial_number(x509.random_serial_number())
            .not_valid_before(datetime.fromtimestamp((START-60000)/1000, timezone.utc))
            .not_valid_after(datetime.fromtimestamp((START+86400000)/1000, timezone.utc))
            .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
            .add_extension(x509.KeyUsage(digital_signature=False, content_commitment=False,
                key_encipherment=False, data_encipherment=False, key_agreement=False,
                key_cert_sign=True, crl_sign=True, encipher_only=False, decipher_only=False), critical=True)
            .sign(private, hashes.SHA256()))
    return private, dict(keyId=hashlib.sha256(cert.public_bytes(serialization.Encoding.DER)).hexdigest(),
                         state='ACTIVE', certificatePem=cert.public_bytes(serialization.Encoding.PEM).decode())


def sign(payload, kid, private, **header):
    protected = encode(document({'alg':'RS256', 'typ':'toolgate-discovery+jws', 'kid':kid, **header}))
    encoded = encode(document(payload))
    value = protected+'.'+encoded
    return dict(jws=value+'.'+encode(private.sign(value.encode(), padding.PKCS1v15(), hashes.SHA256())))


def main():
    first, second, foreign = key(), key(), key()
    ca_private, ca = authority(TENANT)
    _, other_ca = authority(OTHER)
    a, b, c = jwk('discovery-a', first), jwk('discovery-b', second), jwk('discovery-c', foreign)
    def payload(keys, version, issued=0, tenant=TENANT, host='tenant.example.test', issuer=ca):
        origin = 'https://'+host
        return dict(protocolVersion=2, tenantId=tenant, issuer='urn:toolgate:tenant:'+tenant, audiences=[origin], serverId='server', organization='Example tenant',
                    controlUrl=origin, gatewayUrl=origin, verificationUri=origin+'/console/#enroll',
                    minimumClientVersion='0.0.0', issuedAtUnixMs=START+issued,
                    expiresAtUnixMs=START+300000, keySetVersion=version,
                    discoveryKeys=dict(keys=keys), deviceIssuerKeyId=issuer['keyId'], deviceAuthorities=[issuer])
    cases = {
        'initial': sign(payload([a], 1), a['kid'], first),
        'announced': sign(payload([a,b], 2, 1), a['kid'], first),
        'rotated': sign(payload([a,b], 2, 2), b['kid'], second),
        'reordered': sign(payload([b,a], 2, 2), a['kid'], first),
        'retired': sign(payload([b], 3, 3), b['kid'], second),
        'unannounced': sign(payload([b], 2, 2), b['kid'], second),
        'unversioned': sign(payload([a,b], 1, 1), a['kid'], first),
        'revokedSigner': sign(payload([b], 3, 4), a['kid'], first),
        'otherTenant': sign(payload([c], 1, tenant=OTHER, host='other.example.test', issuer=other_ca), c['kid'], foreign),
        'wrongPurpose': sign(payload([a], 1), a['kid'], first, typ='toolgate-fleet-desired+jws'),
        'wrongAlgorithm': sign(payload([a], 1), a['kid'], first, alg='HS256'),
        'remoteKeyHeader': sign(payload([a], 1), a['kid'], first, jku='https://untrusted.invalid/keys'),
        'unknownKey': sign(payload([a], 1), 'unknown-key', first),
        'wrongVersion': sign(dict(payload([a], 1), protocolVersion=1), a['kid'], first),
        'aliasTenant': sign(dict(payload([a], 1), tenantId='quickstart'), a['kid'], first),
        'sharedCaKey': sign(payload([jwk('ca-reuse', ca_private)], 1), 'ca-reuse', ca_private),
    }
    split=payload([a],1)
    split.update(controlUrl='https://control.tenant.example.test:8443',verificationUri='https://control.tenant.example.test:8443/console/#enroll',audiences=['https://control.tenant.example.test:8443','https://tenant.example.test'])
    cases['splitOrigins']=sign(split,a['kid'],first)
    path = ROOT/'tests/fixtures/tenant-discovery/v2.json'
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(dict(now=START+10, origin='https://tenant.example.test',
                                   tenantId=TENANT, otherTenantId=OTHER, cases=cases), indent=2)+'\n')
    corpus = ROOT/'tests/fixtures/contracts/v2'
    valid = json.loads((corpus/'valid.json').read_text())
    models = dict(TenantUuid=TENANT, DiscoveryKeyId=a['kid'],
                  DiscoveryHeader=dict(alg='RS256', typ='toolgate-discovery+jws', kid=a['kid']),
                  DiscoveryPublicKey=a, DiscoveryKeySet=dict(keys=[a]), DiscoveryAuthorityState='ACTIVE',
                  DiscoveryDeviceAuthority=ca, TenantDiscovery=payload([a], 1), SignedTenantDiscovery=cases['initial'])
    valid.update(models)
    (corpus/'valid.json').write_text(json.dumps(valid, indent=2)+'\n')
    invalid = json.loads((corpus/'invalid.json').read_text())
    def change(name, **fields):
        value=deepcopy(models[name]);value.update(fields);return value
    bad = dict(
        TenantUuid=['quickstart', '4A130F27-89BB-4EB9-BB12-3C28473D59E1', None],
        DiscoveryKeyId=['../key', 'https://key', ''],
        DiscoveryHeader=[change('DiscoveryHeader', alg='none'), change('DiscoveryHeader', typ='JWT'), change('DiscoveryHeader', jku='https://untrusted.invalid')],
        DiscoveryPublicKey=[change('DiscoveryPublicKey', d='private'), change('DiscoveryPublicKey', kty='oct'), change('DiscoveryPublicKey', use='enc'), change('DiscoveryPublicKey', e='AQAD'), change('DiscoveryPublicKey', n='weak')],
        DiscoveryKeySet=[dict(keys=[]),dict(keys=[a]*5)],
        DiscoveryAuthorityState=['RETIRED'],
        DiscoveryDeviceAuthority=[change('DiscoveryDeviceAuthority', keyId='short')],
        TenantDiscovery=[change('TenantDiscovery', protocolVersion=1), change('TenantDiscovery', tenantId='default'), change('TenantDiscovery', keySetVersion=0), change('TenantDiscovery', expiresAtUnixMs=9007199254740992),change('TenantDiscovery',audiences=[]),change('TenantDiscovery',issuer='https://untrusted.invalid')],
        SignedTenantDiscovery=[dict(payload='old',signature='old'),dict(jws='a.b.c='),dict(jws='a.b.c',header={})])
    for name, values in bad.items():invalid[name]=[dict(case='discovery boundary '+str(index+1),value=value) for index,value in enumerate(values)]
    (corpus/'invalid.json').write_text(json.dumps(invalid, indent=2)+'\n')
    print('Public discovery v2 vectors generated; no private key material written.')


if __name__ == '__main__': main()
