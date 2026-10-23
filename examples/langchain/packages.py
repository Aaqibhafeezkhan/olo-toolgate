# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Real package build, Control client release and signed fleet rollout for the local example."""
import json
from pathlib import Path
import secrets
import subprocess
import time
import urllib.error
import urllib.request

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
PACKAGE_ID = 'examples.hotfolder'
TOOLS = {PACKAGE_ID+'/'+name for name in ('list', 'read_text', 'write_text')}
BASE = 'eclipse-temurin@sha256:000fd431958bc81a24abe1e8e5f0f0fd3ae365a594bd50aadb20696805f9408c'


def prepare(compose, state, environment):
    """Keep every build on the selected Quickstart CLI; no host JDK or Cargo is needed."""
    state.mkdir(parents=True, exist_ok=True)
    image = environment.get('TOOLGATE_QUICKSTART_IMAGE', 'ololab/olo-toolgate-quickstart:dev')
    subprocess.run(['docker', 'build', '--platform', 'linux/amd64', '-f', str(HERE/'package.Dockerfile'),
        '--build-arg', 'QUICKSTART_IMAGE='+image, '--output', 'type=local,dest='+str(state/'package'),
        str(ROOT)], check=True)
    subprocess.run(compose+['up', '-d', 'registry'], check=True, cwd=HERE)
    port = environment.get('TOOLGATE_REGISTRY_PORT', '18552')
    if not port.isdigit() or not 1024 <= int(port) <= 65535:
        raise ValueError('Registry port must be an unprivileged TCP port')
    origin = 'http://localhost:'+port
    deadline = time.monotonic()+30
    while True:
        try:
            with urllib.request.urlopen(origin+'/v2/', timeout=2) as response:
                if response.status == 200:
                    break
        except OSError:
            pass
        if time.monotonic() >= deadline:
            raise ValueError('The local package registry did not become ready')
        time.sleep(1)
    mirror = 'localhost:'+port+'/toolgate/java-base:approved'
    subprocess.run(['docker', 'pull', '--platform', 'linux/amd64', BASE], check=True)
    subprocess.run(['docker', 'tag', BASE, mirror], check=True)
    subprocess.run(['docker', 'push', mirror], check=True)
    # The pushed single-platform manifest is independently digest-pinned in Control.
    request = urllib.request.Request(origin+'/v2/toolgate/java-base/manifests/approved',
        headers={'Accept': 'application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json'})
    with urllib.request.urlopen(request, timeout=10) as response:
        import hashlib
        digest = 'sha256:'+hashlib.sha256(response.read()).hexdigest()
    settings = dict(TOOLGATE_CONTROL_CLIENT_IMAGES_ENABLED='true',
        TOOLGATE_CONTROL_CLIENT_IMAGES_REGISTRY_URL=origin,
        TOOLGATE_CONTROL_CLIENT_IMAGES_REPOSITORY='toolgate/client-tools',
        TOOLGATE_CONTROL_CLIENT_IMAGES_BASE='toolgate/java-base@'+digest,
        TOOLGATE_CONTROL_CLIENT_IMAGES_PLATFORMS='LINUX,WINDOWS')
    (state/'client-images.env').write_text(''.join(k+'='+v+'\n' for k,v in settings.items()), encoding='utf-8')


def release_request(config, path, body, content_type='application/json'):
    payload = json.dumps(body).encode() if content_type == 'application/json' else body
    request = urllib.request.Request(config.origin+'/api/control/v1/'+path, payload,
        {'Content-Type': content_type, 'Authorization': 'Bearer '+config.tokens['admin'],
         'Idempotency-Key': secrets.token_hex(16)}, method='POST')
    try:
        with urllib.request.urlopen(request, timeout=180) as response:
            return json.load(response)
    except urllib.error.HTTPError as failure:
        raise ValueError(f'Package operation {path} rejected: HTTP {failure.code}') from None


def publish(config, path):
    upload = release_request(config, 'packages/uploads', path.read_bytes(),
        'application/vnd.ololabs.toolgate.package+zip')
    release = release_request(config, 'packages/client-releases', {'packageDigest': upload['packageDigest']})
    exported = {tool['toolId'] for tool in release['document']['tools']}
    if release['packageId'] != PACKAGE_ID or not release.get('signed') or not TOOLS <= exported:
        raise ValueError('Control must sign a HotFolder client release containing every example tool')
    return release


def assign(config, release, device):
    rollout = config.api('/api/control/v1/fleet/rollouts', dict(id='langchain-'+secrets.token_hex(12),
        packageId=release['packageId'], version=release['version'], deviceIds=[device],
        desiredPresence=True, percentage=100))
    rollout_id = rollout['id']
    deadline = time.monotonic()+240
    while True:
        rows = config.api('/api/control/v1/fleet/rollouts')['items']
        current = next((row for row in rows if row['rollout']['id'] == rollout_id), None)
        if current and current['failed']:
            raise ValueError('HotFolder package activation failed; inspect the device report and logs')
        if current and current['ready'] == 1:
            return
        if time.monotonic() >= deadline:
            raise ValueError('The selected device did not report the signed HotFolder rollout READY')
        time.sleep(2)
