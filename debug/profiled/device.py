# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Installation-owned package execution boundary; no workflow or seeded inputs."""
import json
from pathlib import Path
import socket
import time

import device_entrypoint


def configure(path):
    settings=json.loads(path.read_text(encoding='utf-8'))
    trust=Path('/installation-trust/trust.json')
    deadline=time.monotonic()+120
    while not trust.is_file():
        if time.monotonic()>deadline:raise ValueError('Installation deployment trust unavailable')
        time.sleep(1)
    settings['deployment']=json.loads(trust.read_text(encoding='utf-8'))
    root=Path('/var/lib/olo-toolgate/files')
    root.mkdir(mode=0o700,exist_ok=True)
    root.chmod(0o700)
    engine=Path('/run')/('toolgate-'+socket.gethostname()+'.sock')
    if not engine.exists():engine.symlink_to('/var/run/docker.sock')
    # Fleet reconciliation owns the persisted runtime/tool inventory after first
    # installation. Restarting the container must not empty its applied packages.
    if settings.get('execution') is None:
        settings['execution']=dict(enginePath='/usr/bin/docker',engineEndpoint='unix://'+str(engine),
            stateDirectory='/var/lib/olo-toolgate/runtimes',allowFirstUsePull=True,pullTimeoutSeconds=180,
            runtimes=[],tools=[],files=dict(root=str(root),maxFileBytes=65536,maxEntries=256,
                                         extensions=['txt','md','json','csv','log']))
    path.write_text(json.dumps(settings),encoding='utf-8')


if __name__=='__main__':
    device_entrypoint.main(configure=configure)
