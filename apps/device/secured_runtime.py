# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Run the ordinary native enrollment and supervision flow without firewall privileges."""
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import threading

import device_entrypoint as device
from secured_network import settings


def configure_tools(path, tools):
    """Offer the named built-in tools from the client's own profiles; files stay in the device's private HotFolder.

    Enrollment still grants nothing: Control allocates these tools only through its usual groups and bindings.
    """
    settings = json.loads(path.read_text())
    state = Path(settings['stateDirectory'])
    folder = state/'hotfolder'
    folder.mkdir(mode=0o700, exist_ok=True)
    profiles = [item for item in json.loads(device.cli('authorization-profiles')) if item['tool']['id'] in tools]
    missing = tools - {item['tool']['id'] for item in profiles}
    if missing:
        raise ValueError('TOOLGATE_DEVICE_TOOLS names tools this client does not offer: '+', '.join(sorted(missing)))
    # The service requires a private token file. This inert value grants no local IPC rights; remote tasks
    # authorize through the enrolled device's mTLS identity and Control permits.
    token = state/'local-ipc-disabled-token'
    if not token.exists():
        with token.open('x', encoding='utf-8') as stream:
            stream.write(secrets.token_urlsafe(48))
        token.chmod(0o600)
    settings['tools'] = dict(hotfolder=dict(root=str(folder), maxFileBytes=65536, maxEntries=256,
                                            extensions=['txt', 'md', 'json', 'csv']),
                             gatewayUrl=settings['serverUrl'], gatewayTokenPath=str(token),
                             gatewayCaPath=settings['caCertificatePath'], deviceId='remote',
                             authorizationProfiles=profiles, webSearchTokenPath=None, webSearchAllowedDomains=[])
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(settings))
    temporary.chmod(0o600)
    temporary.replace(path)


def main():
    status = dict(line.split(':', 1) for line in Path('/proc/self/status').read_text().splitlines()
                  if ':' in line)
    if any(int(status[key].strip(), 16) for key in ('CapEff', 'CapPrm', 'CapBnd', 'CapInh', 'CapAmb')):
        raise RuntimeError('The device service must have no Linux capabilities')
    config = settings(os.environ)
    for key in list(os.environ):
        if key.lower() in ('http_proxy', 'https_proxy', 'all_proxy', 'no_proxy'):
            del os.environ[key]
    signal.signal(signal.SIGTERM, lambda *_: device.STOP.set())
    signal.signal(signal.SIGINT, lambda *_: device.STOP.set())
    path = Path('/etc/olo-toolgate/client.json')
    for directory, mode in ((path.parent, 0o755), (Path('/var/lib/olo-toolgate'), 0o700),
                            (Path(device.CLIENT).parent, 0o755)):
        directory.mkdir(parents=True, exist_ok=True, mode=mode)
        directory.chmod(mode)
    if path.exists():
        saved = json.loads(path.read_text())
        if saved['serverUrl'] != config['origin']:
            raise ValueError('Existing device belongs to another Gateway; use separate identity volumes')
        if Path(saved['caCertificatePath']).read_bytes() != Path(config['ca']).read_bytes():
            raise ValueError('Gateway CA changed; use separate device volumes and approve a new enrollment')
    else:
        # The service uses the same fixed Linux configuration as the native installer.
        # No HTTP trust bootstrap, browser download, or secondary network port is needed.
        trust = path.parent/'gateway-trust.crt'
        trust.write_bytes(Path(config['ca']).read_bytes())
        trust.chmod(0o644)
        saved = dict(serverUrl=config['origin'], stateDirectory='/var/lib/olo-toolgate',
                     ipcEndpoint='/run/olo-toolgate/client.sock', authorizedPeers=['0'],
                     caCertificatePath=str(trust), requestTimeoutSeconds=10)
        temporary = path.with_suffix('.tmp')
        temporary.write_text(json.dumps(saved))
        temporary.chmod(0o600)
        temporary.replace(path)
    shutil.copyfile('/opt/toolgate/client', device.CLIENT)
    Path(device.CLIENT).chmod(0o755)
    tools = {name.strip() for name in os.environ.get('TOOLGATE_DEVICE_TOOLS', '').split(',') if name.strip()}
    if tools:
        configure_tools(path, tools)
    relay = device.Relay(config['port'], config['address'], config['upstream_port'])
    thread = threading.Thread(target=relay.serve_forever, daemon=True)
    thread.start()
    try:
        device.supervise()
    finally:
        relay.shutdown()
        thread.join(timeout=5)
        relay.server_close()


if __name__ == '__main__':
    try:
        main()
    except Exception as failure:
        print('Secured device failed: '+str(failure), flush=True)
        raise SystemExit(1)
