# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Real container gate: delayed gateway, enrollment, mTLS, retained identity and reconnection."""
import argparse
import json
from pathlib import Path
import secrets
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]


def run(*args, **kwargs):
    return subprocess.run(args, cwd=ROOT, check=True, text=True, capture_output=True, **kwargs).stdout.strip()


def wait(operation, description, timeout=180):
    deadline = time.monotonic()+timeout
    while time.monotonic() < deadline:
        try:
            value = operation()
            if value:
                return value
        except (OSError, ValueError, subprocess.SubprocessError):
            pass
        time.sleep(1)
    raise RuntimeError('Timed out: '+description)


def check(image, quickstart):
    name = 'toolgate-device-check-'+secrets.token_hex(6)
    gateway = name+'-gateway'
    client = name+'-client'
    alias = name+'-alias'
    network = name+'-network'
    volumes = [name+'-'+suffix for suffix in ('data', 'config', 'state', 'alias-config', 'alias-state')]
    gates = []
    def mark(message):
        gates.append(message)
        print('PASS '+message, flush=True)
    def status():
        return json.loads(run('docker', 'exec', client, 'cat', '/run/olo-toolgate/device-status.json'))
    try:
        run('docker', 'network', 'create', network)
        for volume in volumes:
            run('docker', 'volume', 'create', volume)
        run('docker', 'run', '-d', '--name', gateway, '--read-only', '--cap-drop=ALL',
            '--network', network, '--network-alias', 'container_name_2',
            '--security-opt=no-new-privileges', '--memory=1536m', '--cpus=2',
            '--tmpfs', '/tmp:rw,noexec,nosuid,size=128m,uid=65532,gid=65532',
            '-e', 'TOOLGATE_CONTROL_ENDPOINT_CONTROL_URL=https://localhost:18452',
            '-e', 'TOOLGATE_CONTROL_ENDPOINT_GATEWAY_URL=https://localhost:18452',
            '-p', '127.0.0.1::8080', '-v', volumes[0]+':/data',
            '--entrypoint', '/opt/quickstart-python/bin/python', quickstart, '-c',
            "import sys;sys.path.insert(0,'/opt/quickstart');import supervisor;s=supervisor;s.tls_bridges=lambda:[];s.main()")
        def origin():
            info = json.loads(run('docker', 'inspect', gateway))[0]
            port = info['NetworkSettings']['Ports']['8080/tcp'][0]['HostPort']
            return 'http://127.0.0.1:'+port
        def api(path, body=None, token=None):
            headers = {'Content-Type': 'application/json', 'Idempotency-Key': secrets.token_hex(16)}
            if token:
                headers['Authorization'] = 'Bearer '+token
            request = urllib.request.Request(origin()+path,
                json.dumps(body).encode() if body is not None else None, headers)
            with urllib.request.urlopen(request, timeout=10) as response:
                return json.load(response)
        wait(lambda: urllib.request.urlopen(origin()+'/health/ready', timeout=3).close() or True,
             'Quickstart readiness')
        ca = run('docker', 'exec', gateway, 'cat', '/data/keys/device-ca.crt')
        # Only the public certificate is copied. No gateway key/state is mounted in the device.
        run('docker', 'run', '--rm', '-i', '--entrypoint', 'python', '-v', volumes[1]+':/etc/olo-toolgate',
            image, '-c', "import sys;from pathlib import Path;p=Path('/etc/olo-toolgate/gateway-ca.crt');p.write_text(sys.stdin.read());p.chmod(0o644)", input=ca)
        def start_client():
            run('docker', 'run', '-d', '--name', client, '--cap-drop=ALL',
                '--security-opt=no-new-privileges', '--memory=256m', '--network', 'container:'+gateway,
                '--tmpfs', '/run:rw,nosuid,size=8m,mode=755',
                '-e', 'TOOLGATE_GATEWAY_URLS=https://localhost:49999, https://localhost:18452',
                '-e', 'TOOLGATE_GATEWAY_CA_FILE=/etc/olo-toolgate/gateway-ca.crt',
                '-v', volumes[1]+':/etc/olo-toolgate', '-v', volumes[2]+':/var/lib/olo-toolgate', image)
        start_client()
        wait(lambda: status().get('state') == 'WAITING_GATEWAY', 'device waiting for offline gateways')
        # Leave both listed URLs unavailable long enough to exercise another polling cycle.
        time.sleep(6)
        assert status()['state'] == 'WAITING_GATEWAY'
        def start_proxy():
            run('docker', 'exec', '-i', gateway, '/opt/quickstart-python/bin/python', '-c',
                "import sys;from pathlib import Path;Path('/tmp/device_relay.py').write_text(sys.stdin.read())",
                input=(ROOT/'debug/device-entrypoint.py').read_text(encoding='utf-8'))
            run('docker', 'exec', '-d', gateway, '/opt/quickstart-python/bin/python', '-B', '-c',
                "import sys,os;from pathlib import Path;Path('/tmp/device-relay.pid').write_text(str(os.getpid()));sys.path.insert(0,'/tmp');import device_relay;device_relay.Relay(18452,'127.0.0.1',8443).serve_forever()")
        start_proxy()
        pending = wait(lambda: (value if value.get('userCode') else None) if (value := status()) else None,
                       'device enrollment after the second gateway comes online')
        mark('comma-separated gateways/delayed availability/automatic enrollment')
        initial = run('docker', 'exec', gateway, 'cat', '/data/bootstrap-password')
        login = api('/api/quickstart/v1/login', dict(username='admin', password=initial,
                                                  newPassword=secrets.token_urlsafe(32)))
        token = login['accessToken']
        review = api('/api/control/v1/endpoint/enrollments/review?code='+pending['userCode'], token=token)
        assert review['keyFingerprint'] == pending['keyFingerprint']
        api('/api/control/v1/endpoint/enrollments/decision',
            dict(userCode=review['userCode'], keyFingerprint=review['keyFingerprint'], choice='APPROVE'), token)
        device_id = pending['deviceId']
        def checked_in(after=0):
            row = api('/api/control/v1/endpoint/devices/'+device_id, token=token)
            return row if row['state'] == 'ACTIVE' and row.get('lastSeenUnixMs', 0) > after else None
        wait(checked_in, 'real device mTLS check-in')
        mark('fingerprint-reviewed enrollment/real mTLS check-in')
        run('docker', 'run', '-d', '--name', alias, '--cap-drop=ALL', '--security-opt=no-new-privileges',
            '--network', network, '--tmpfs', '/run:rw,nosuid,size=8m,mode=755',
            '-e', 'TOOLGATE_GATEWAY_URLS=container_name_1:8443, container_name_2:8443',
            '-v', volumes[3]+':/etc/olo-toolgate', '-v', volumes[4]+':/var/lib/olo-toolgate', image)
        def alias_status():
            return json.loads(run('docker', 'exec', alias, 'cat', '/run/olo-toolgate/device-status.json'))
        alias_prompt = wait(lambda: (value if value.get('userCode') else None) if (value := alias_status()) else None,
                            'container-name shorthand and local CA bootstrap')
        alias_review = api('/api/control/v1/endpoint/enrollments/review?code='+alias_prompt['userCode'], token=token)
        assert alias_review['keyFingerprint'] == alias_prompt['keyFingerprint']
        api('/api/control/v1/endpoint/enrollments/decision',
            dict(userCode=alias_review['userCode'], keyFingerprint=alias_review['keyFingerprint'], choice='APPROVE'), token)
        wait(lambda: api('/api/control/v1/endpoint/devices/'+alias_prompt['deviceId'], token=token)
             .get('lastSeenUnixMs'), 'real shorthand device mTLS check-in')
        mark('container_name:8443 shorthand/Docker DNS/public CA bootstrap/verified native mTLS')
        run('docker', 'stop', '-t', '25', client)
        run('docker', 'rm', client)
        start_client()
        wait(lambda: status().get('deviceId') == device_id and status().get('state') == 'ACTIVE',
             'retained device identity after recreation')
        checksums = run('docker', 'exec', client, 'sha256sum', '/opt/toolgate/client',
                        '/usr/local/lib/olo-toolgate/olo-toolgate-client').splitlines()
        assert checksums[0].split()[0] == checksums[1].split()[0]
        mark('container recreation retains identity/installs current image executable')
        run('docker', 'exec', gateway, '/opt/quickstart-python/bin/python', '-c',
            "import os,signal;from pathlib import Path;os.kill(int(Path('/tmp/device-relay.pid').read_text()),signal.SIGTERM)")
        time.sleep(10)
        assert json.loads(run('docker', 'inspect', client))[0]['State']['Running']
        reconnect_after = int(time.time()*1000)
        start_proxy()
        wait(lambda: checked_in(reconnect_after), 'fresh device check-in after gateway outage', timeout=360)
        mark('gateway outage/native retry/fresh mTLS reconnection')
        output = ROOT/'build/device'
        output.mkdir(parents=True, exist_ok=True)
        (output/'smoke.json').write_text(json.dumps(dict(image=image, quickstartImage=quickstart,
            clientSha256=checksums[0].split()[0], gates=gates), indent=2)+'\n', encoding='utf-8')
    except BaseException:
        # A timed-out gate otherwise hides why a device never reached its next state.
        for container in (alias, client, gateway):
            logs = subprocess.run(['docker', 'logs', '--tail', '80', container], cwd=ROOT,
                                  capture_output=True, text=True)
            print(f'--- {container} logs ---\n{logs.stdout}{logs.stderr}', flush=True)
        raise
    finally:
        for container in (alias, client, gateway):
            subprocess.run(['docker', 'rm', '-f', container], cwd=ROOT, capture_output=True)
        for volume in volumes:
            subprocess.run(['docker', 'volume', 'rm', volume], cwd=ROOT, capture_output=True)
        subprocess.run(['docker', 'network', 'rm', network], cwd=ROOT, capture_output=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', default='olo-toolgate-device:module11')
    parser.add_argument('--quickstart-image', default='olo-toolgate-quickstart:module11')
    arguments = parser.parse_args()
    check(arguments.image, arguments.quickstart_image)
