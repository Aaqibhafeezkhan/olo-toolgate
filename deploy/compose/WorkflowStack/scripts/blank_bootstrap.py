# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Initialize an empty deployment profile in root-owned immutable bootstrap custody."""
import json
from pathlib import Path

from bootstrap import atomic


def initialize(output):
    if output.is_symlink():
        raise ValueError('Linked blank bootstrap custody')
    output.mkdir(mode=0o755, exist_ok=True)
    info = output.stat()
    if info.st_uid != 0 or info.st_mode & 0o022:
        raise ValueError('Blank bootstrap custody must be root-owned and protected')
    marker = output / 'format.json'
    if marker.exists():
        if marker.is_symlink() or json.loads(marker.read_bytes()) != {'formatVersion': 1}:
            raise ValueError('Unsupported blank bootstrap format')
    elif any(output.iterdir()):
        raise ValueError('Unmarked blank bootstrap custody')
    if {path.name for path in output.iterdir()} - {'format.json', 'profile.json'}:
        raise ValueError('Unexpected blank bootstrap content')
    atomic(output / 'profile.json', b'{"profileVersion":2,"profileId":"blank","packages":[]}\n')
    atomic(marker, b'{"formatVersion":1}\n')


if __name__ == '__main__':
    initialize(Path('/bootstrap'))
