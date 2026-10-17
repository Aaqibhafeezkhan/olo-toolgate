# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Administrator-mounted execution and release trust for a lab device (ADR 026 and 027).

The device runs signed package tools only when its protected client configuration has an ``execution`` section (the
container engine, managed-runtime state and optional files area) and a ``deployment`` section (the release and
organization trust keys that verify fleet documents). They come from one file the deployment mounts read-only and names
in ``TOOLGATE_DEVICE_EXECUTION_FILE``; nothing is fetched over the network, and identity, enrollment and Gateway settings
are never taken from it. Without the variable the device keeps whatever its configuration already has.
"""
import json
from pathlib import Path

VARIABLE = 'TOOLGATE_DEVICE_EXECUTION_FILE'
SECTIONS = ('execution', 'deployment')
STATE = '/var/lib/olo-toolgate'


def load(environ):
    """The mounted sections, or None when the deployment mounts none."""
    name = environ.get(VARIABLE)
    if not name:
        return None
    path = Path(name)
    if not path.is_absolute() or not path.is_file() or path.stat().st_size > 65536:
        raise ValueError('Mount the device execution file (at most 64 KiB) at an absolute path')
    value = json.loads(path.read_text(encoding='utf-8'))
    if not isinstance(value, dict) or not set(value) <= set(SECTIONS) or 'execution' not in value:
        raise ValueError('The device execution file holds an execution section and optionally a deployment section')
    if not isinstance(value['execution'], dict) or value['execution'].get('stateDirectory') != STATE + '/runtimes':
        raise ValueError('execution.stateDirectory must be ' + STATE + '/runtimes')
    if 'deployment' in value and not isinstance(value['deployment'], dict):
        raise ValueError('deployment must be an object')
    return value


def merge(saved, mounted):
    """The client configuration with the mounted sections in place of its own; whether anything changed."""
    if mounted is None:
        return saved, False
    merged = {key: value for key, value in saved.items() if key not in SECTIONS}
    merged.update(mounted)
    return merged, merged != saved
