# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Restore only the local admin verifier into an otherwise fresh Quickstart volume."""
import json
import os
from pathlib import Path


def initialize(source,output):
    if source.is_symlink() or output.is_symlink():raise ValueError('Linked reset custody')
    info=output.stat()
    if info.st_uid not in (0,65532) or info.st_mode&0o022:raise ValueError('Unprotected reset data storage')
    data=source.read_bytes()
    if len(data)>16384 or not isinstance(json.loads(data),dict):raise ValueError('Invalid admin verifier')
    target=output/'identity.json'
    if target.is_symlink():raise ValueError('Linked identity custody')
    if target.exists():
        if target.read_bytes()!=data:raise ValueError('Existing admin identity differs; use an explicit fresh reset')
        if target.stat().st_uid!=65532 or target.stat().st_mode&0o077:raise ValueError('Unprotected admin identity')
        return
    if any(output.iterdir()):raise ValueError('Admin restore requires fresh data storage')
    with target.open('xb') as stream:stream.write(data)
    os.chown(target,65532,65532);target.chmod(0o600)


if __name__=='__main__':initialize(Path('/input/identity.json'),Path('/data'))
