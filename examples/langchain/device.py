# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Opt-in example configuration around the shared real endpoint supervisor."""
import json
from pathlib import Path

import device_entrypoint

FIXTURES = Path('/opt/toolgate/fixtures')

def configure(path):
    settings = json.loads(path.read_text())
    state = Path(settings['stateDirectory'])
    folder = state / 'hotfolder'
    folder.mkdir(mode=0o700, exist_ok=True)
    # Seed only missing, fictional inputs. Existing inputs and generated reports are preserved.
    for fixture in FIXTURES.iterdir():
        target = folder / fixture.name
        if not target.exists():
            with target.open('x', encoding='utf-8') as stream:
                stream.write(fixture.read_text(encoding='utf-8'))
            target.chmod(0o600)
    # Manage installs PUBLIC release/deployment trust explicitly. Tools arrive only
    # through signed fleet assignments, and every effect still consumes an mTLS permit.
    settings.pop('tools', None)
    settings['execution'] = dict(enginePath='/usr/bin/docker', engineEndpoint='unix:///var/run/docker.sock',
        stateDirectory=str(state/'runtimes'), allowFirstUsePull=True, pullTimeoutSeconds=180,
        runtimes=[], tools=[], files=dict(root=str(folder), maxFileBytes=65536,
            maxEntries=256, extensions=['txt', 'md', 'json', 'csv']))
    path.write_text(json.dumps(settings), encoding='utf-8')


if __name__ == '__main__':
    device_entrypoint.main(configure=configure)
