# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Install and supervise a real Linux client, with loopback forwarding to debug Quickstart."""
import json
import os
from pathlib import Path
import re
import select
import shutil
import signal
import socket
import socketserver
import subprocess
import threading
import time
import urllib.request

CLIENT = '/usr/local/lib/olo-toolgate/olo-toolgate-client'
UNIT = Path('/etc/systemd/system/olo-toolgate-client.service')
JOURNAL = Path('/var/lib/olo-toolgate/journal.json')
# Gateway-scoped enrollment state. The device key and HotFolder files are kept.
GATEWAY_STATE = ('journal.json', 'permissions.json', 'remote-journal.json', 'effect-journal.json',
                 'adoption.json', 'fleet-intent.json', 'fleet-active.json')
STATUS = Path('/run/olo-toolgate/device-status.json')
STOP = threading.Event()


class Relay(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self, local_port, upstream, upstream_port):
        self.upstream = (upstream, upstream_port)
        super().__init__(('127.0.0.1', local_port), Forward)


class Forward(socketserver.BaseRequestHandler):
    def handle(self):
        try:
            with socket.create_connection(self.server.upstream, timeout=10) as remote:
                remote.settimeout(None)
                # Forward bytes unchanged, including TLS and WebSockets. The client validates TLS.
                peers = {self.request: remote, remote: self.request}
                readers = list(peers)
                while readers and not STOP.is_set():
                    readable, _, _ = select.select(readers, [], [], 1)
                    for source in readable:
                        data = source.recv(65536)
                        if data:
                            peers[source].sendall(data)
                        else:
                            peers[source].shutdown(socket.SHUT_WR)
                            readers.remove(source)
        except OSError:
            # A closed upstream connection must reach the client's ordinary retry handling.
            return


def cli(operation):
    result = subprocess.run([CLIENT, operation], capture_output=True, text=True, timeout=25)
    if result.returncode:
        raise RuntimeError(f'Client {operation} failed: {result.stderr.strip()}')
    return result.stdout


def enrollment():
    output = cli('enroll')
    fingerprint = re.search(r'fingerprint in your browser: ([a-f0-9]{64})', output)
    code = re.search(r'[?&]code=([A-F0-9]{16})', output)
    expires = re.search(r'Enrollment expires at (\d+)', output)
    if not (fingerprint and code and expires):
        raise RuntimeError('Client did not return an enrollment code and fingerprint.')
    prompt = {'deviceId': 'device-' + fingerprint[1][:32], 'keyFingerprint': fingerprint[1],
              'userCode': code[1], 'expiresAtUnixMs': int(expires[1])}
    print(f'Needs approval: {prompt["deviceId"]}; code={prompt["userCode"]}; '
          f'fingerprint={prompt["keyFingerprint"]}', flush=True)
    return prompt


def publish(health, prompt):
    status = {'platform': 'LINUX', 'systemName': socket.gethostname(),
              'updatedAtUnixMs': int(time.time() * 1000), **health}
    if prompt:
        status.update(prompt)
    if JOURNAL.exists():
        journal = json.loads(JOURNAL.read_text())
        identity = journal.get('identity')
        if identity:
            status['deviceId'] = identity['deviceId']
            # Enrollment codes are no longer useful once the identity has been issued.
            status.pop('userCode', None)
            status.pop('expiresAtUnixMs', None)
    temporary = STATUS.with_suffix('.tmp')
    temporary.write_text(json.dumps(status))
    temporary.replace(STATUS)


def certificate_body(pem):
    return ''.join(line.strip() for line in pem.strip().splitlines() if not line.startswith('-----'))


def gateway_recreated(settings, http_port):
    """True when the Quickstart now publishes a CA other than the one this device trusts."""
    try:
        with urllib.request.urlopen(f'http://127.0.0.1:{http_port}/api/public/v1/clients/local-trust',
                                    timeout=5) as response:
            trust = json.loads(response.read(20001))
    except (OSError, ValueError):
        # Never discard enrollment because of a transient console failure.
        return False
    if trust.get('serverUrl') != settings['serverUrl']:
        return False
    installed = settings.get('caCertificatePath')
    if not installed or not Path(installed).is_file():
        return True
    return certificate_body(Path(installed).read_text()) != certificate_body(trust['caCertificatePem'])


def reset_gateway(config, settings):
    """A recreated gateway issues new identities; the old enrollment can never check in again."""
    state = Path(settings['stateDirectory'])
    for name in GATEWAY_STATE:
        (state / name).unlink(missing_ok=True)
    for path in (config, Path(CLIENT), UNIT):
        path.unlink(missing_ok=True)


def supervise():
    """Keep the installed service and its ordinary gateway retry loop in the foreground."""
    STATUS.parent.mkdir(mode=0o755, parents=True, exist_ok=True)
    service = subprocess.Popen([CLIENT, 'service'])
    try:
        deadline = time.monotonic() + 60
        while not STOP.is_set():
            if service.poll() is not None:
                raise RuntimeError('The protected client service exited during startup.')
            try:
                health = json.loads(cli('health'))
                break
            except (RuntimeError, subprocess.TimeoutExpired, ValueError):
                if time.monotonic() >= deadline:
                    raise RuntimeError('The protected client IPC did not become ready.')
                STOP.wait(1)
        if STOP.is_set():
            return
        prompt = None
        enroll_due = health['state'] in ('UNENROLLED', 'PENDING')
        previous = health['state']
        while not STOP.is_set():
            if service.poll() is not None:
                raise RuntimeError('The protected client service exited.')
            try:
                health = json.loads(cli('health'))
            except (RuntimeError, subprocess.TimeoutExpired, ValueError):
                # Native health can be busy while signed package activation holds
                # the service lock. Mark unhealthy and retry; killing a live service
                # here would repeatedly interrupt its bounded pull/self-tests.
                STATUS.unlink(missing_ok=True)
                print('Client health unavailable; service is running, retrying.', flush=True)
                STOP.wait(5)
                continue
            # An expired request can be renewed; a deliberate denial stops automatic enrollment.
            if previous == 'PENDING' and health['state'] == 'UNENROLLED' and prompt:
                enroll_due = time.time() * 1000 >= prompt['expiresAtUnixMs']
                if not enroll_due:
                    prompt = None
            if enroll_due and health['state'] in ('UNENROLLED', 'PENDING'):
                try:
                    prompt = enrollment()
                    enroll_due = False
                    health = json.loads(cli('health'))
                except (RuntimeError, subprocess.TimeoutExpired, ValueError):
                    print('Gateway enrollment unavailable; retrying in 5 seconds.', flush=True)
            publish(health, prompt)
            previous = health['state']
            STOP.wait(5)
    finally:
        if service.poll() is None:
            service.terminate()
            try:
                service.wait(timeout=20)
            except subprocess.TimeoutExpired:
                service.kill()
                service.wait()


def main(configure=None):
    signal.signal(signal.SIGTERM, lambda *_: STOP.set())
    signal.signal(signal.SIGINT, lambda *_: STOP.set())
    upstream = os.environ['TOOLGATE_DEVICE_UPSTREAM']
    http_port = int(os.environ.get('TOOLGATE_DEVICE_HTTP_PORT', '18090'))
    tls_port = int(os.environ.get('TOOLGATE_DEVICE_TLS_PORT', '18450'))
    server_url = os.environ.get('TOOLGATE_DEVICE_SERVER_URL', f'https://localhost:{tls_port}')
    relays = [Relay(http_port, upstream, int(os.environ.get('TOOLGATE_DEVICE_UPSTREAM_HTTP_PORT', '8080'))),
              Relay(tls_port, upstream, int(os.environ.get('TOOLGATE_DEVICE_UPSTREAM_TLS_PORT', '8443')))]
    for relay in relays:
        threading.Thread(target=relay.serve_forever, daemon=True).start()
    try:
        wait_seconds = int(os.environ.get('TOOLGATE_DEVICE_WAIT_TIMEOUT', '90'))
        if wait_seconds < 0:
            raise ValueError('Device wait timeout cannot be negative')
        deadline = time.monotonic() + wait_seconds if wait_seconds else float('inf')
        while not STOP.is_set():
            STATUS.parent.mkdir(mode=0o755, parents=True, exist_ok=True)
            publish({'state': 'WAITING_GATEWAY', 'gatewayUrls': [server_url]}, None)
            try:
                with urllib.request.urlopen(f'http://127.0.0.1:{http_port}/health/ready', timeout=3) as response:
                    if response.status == 200:
                        break
            except OSError:
                pass
            if time.monotonic() >= deadline:
                raise RuntimeError('Debug Quickstart is not ready or reachable on its Docker network.')
            STOP.wait(1)
        if STOP.is_set():
            return
        config = Path('/etc/olo-toolgate/client.json')
        if config.exists():
            settings = json.loads(config.read_text())
            if settings['serverUrl'] != server_url:
                raise RuntimeError('Existing device belongs to a different gateway.')
            if gateway_recreated(settings, http_port):
                print('Gateway certificate changed (recreated Quickstart); re-enrolling this device.',
                      flush=True)
                reset_gateway(config, settings)
        if not config.exists():
            print('Installing client and verifying gateway certificate trust.', flush=True)
            # Resolve loopback console metadata and verify the advertised HTTPS CA.
            # Supplying the console also supports non-default TLS ports securely.
            subprocess.run(['/opt/toolgate/client', 'install', '--server',
                            f'http://127.0.0.1:{http_port}'],
                           check=True, timeout=60)
            if json.loads(config.read_text())['serverUrl'] != server_url:
                raise RuntimeError('Installed device resolved a different gateway.')
        else:
            # A recreated container gets the current executable and retains its protected identity.
            shutil.copyfile('/opt/toolgate/client', CLIENT)
            os.chmod(CLIENT, 0o755)
        if configure is not None:
            configure(config)
        supervise()
    finally:
        for relay in relays:
            relay.shutdown()
            relay.server_close()


if __name__ == '__main__':
    try:
        main()
    except Exception as failure:
        print(f'Device startup failed: {failure}', flush=True)
        raise SystemExit(1)
