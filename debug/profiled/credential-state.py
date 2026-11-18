# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Read-only local Docker custody helper; stdout must be captured, never logged."""
import hashlib
import hmac
import json
from pathlib import Path
import sys


def resolve(root,candidate):
    record=json.loads((root/'identity.json').read_text(encoding='utf-8'))
    def valid(value):
        if not isinstance(value,str) or not 1<=len(value)<=128:return False
        actual=hashlib.pbkdf2_hmac('sha256',value.encode(),bytes.fromhex(record['salt']),600000).hex()
        return hmac.compare_digest(actual,record['hash'])
    bootstrap=root/'bootstrap-password'
    initial=bootstrap.read_text(encoding='utf-8').strip() if bootstrap.is_file() else None
    initial_valid=valid(initial)
    if valid(candidate):password=candidate;source='saved'
    elif initial_valid:password=initial;source='initial'
    else:return dict(state='PASSWORD_REQUIRED')
    return dict(state='READY',source=source,password=password,initialAvailable=initial_valid,
                ca=(root/'keys/device-ca.crt').read_text(encoding='ascii'))


if __name__=='__main__':
    payload=json.loads(sys.stdin.read(16385))
    if set(payload)!={'password'}:raise ValueError('Invalid local credential request')
    print(json.dumps(resolve(Path('/data'),payload['password'])))
