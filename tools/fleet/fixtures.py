# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Public fleet vectors for the current closed registration contract. Private keys are discarded."""
import base64
import copy
import hashlib
import json
from pathlib import Path
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding, rsa
ROOT=Path(__file__).resolve().parents[2]
def main():
    path=ROOT/'tests/fixtures/fleet/v1/signed.json'
    fixture=json.loads(path.read_text(encoding='utf-8'))
    current=json.loads((ROOT/'tests/fixtures/contracts/v1/valid.json').read_text(encoding='utf-8'))
    keys={name:rsa.generate_private_key(public_exponent=65537,key_size=2048) for name in ['release','organization']}
    def b64(raw):return base64.urlsafe_b64encode(raw).rstrip(b'=').decode()
    def public(name):
        n=keys[name].public_key().public_numbers().n
        return {'kid':name,'n':b64(n.to_bytes((n.bit_length()+7)//8,'big')),'e':'AQAB'}
    def sign(raw,name,typ):
        header=b64(json.dumps({'alg':'RS256','typ':typ,'kid':name},separators=(',',':')).encode())
        message=header+'.'+b64(raw)
        return {'jws':message+'.'+b64(keys[name].sign(message.encode(),padding.PKCS1v15(),hashes.SHA256()))}
    document=fixture['document'];document['runtimes']=[copy.deepcopy(current['ManagedRuntime'])];document['tools']=[copy.deepcopy(current['LocalToolRegistration'])]
    raw=json.dumps(document,separators=(',',':')).encode()
    release={'packageId':document['packageId'],'version':document['version'],'manifestDigest':hashlib.sha256(raw).hexdigest(),'sizeBytes':len(raw),'release':sign(raw,'release','toolgate-package-release+jws')}
    desired=fixture['desired'];desired['assignments'][0]['release']=release
    fixture.update(releaseKeys=[public('release')],organizationKeys=[public('organization')],release=release,signedDesired=sign(json.dumps(desired,separators=(',',':')).encode(),'organization','toolgate-fleet-desired+jws'))
    path.write_text(json.dumps(fixture,indent=2)+'\n',encoding='utf-8',newline='\n')
if __name__=='__main__':main()
