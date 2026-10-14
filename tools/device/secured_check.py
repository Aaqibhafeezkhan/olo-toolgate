# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Real enrollment and network-confinement gates for the secured Linux device."""
import argparse
import json
from pathlib import Path
import secrets
import subprocess
import tempfile
import time
import urllib.request

from check import ROOT, run, wait

DROP = ['setpriv', '--bounding-set=-all', '--inh-caps=-all', '--ambient-caps=-all', '--no-new-privs']
PEER = '''import http.server,socket,socketserver,threading,time
class V6(http.server.ThreadingHTTPServer):
    address_family=socket.AF_INET6
    def server_bind(self):
        self.socket.setsockopt(socket.IPPROTO_IPV6,socket.IPV6_V6ONLY,1)
        super().server_bind()
class UDP(socketserver.BaseRequestHandler):
    def handle(self):
        from pathlib import Path
        path=Path('/tmp/udp-received')
        path.write_text(str(int(path.read_text())+1) if path.exists() else '1')
        self.request[1].sendto(b'received',self.client_address)
for server in (http.server.ThreadingHTTPServer(('0.0.0.0',8123),http.server.BaseHTTPRequestHandler),
               V6(('::',8123),http.server.BaseHTTPRequestHandler),socketserver.UDPServer(('0.0.0.0',8124),UDP)):
    threading.Thread(target=server.serve_forever,daemon=True).start()
print('READY',flush=True)
while True: time.sleep(1)
'''
PROBE = '''import socket,sys
host,port,protocol=sys.argv[1],int(sys.argv[2]),sys.argv[3]
family=socket.AF_INET6 if ':' in host else socket.AF_INET
with socket.socket(family,socket.SOCK_DGRAM if protocol=='udp' else socket.SOCK_STREAM) as s:
    s.settimeout(1)
    try:
        if protocol=='udp': s.sendto(b'probe',(host,port));s.recvfrom(1024)
        else: s.connect((host,port))
    except OSError: print('BLOCKED')
    else: print('REACHABLE')
'''


def check(image, quickstart):
    name = 'toolgate-secured-check-'+secrets.token_hex(5)
    gateway, client, peer = (name+'-'+suffix for suffix in ('gateway', 'client', 'peer'))
    network = name+'-network'
    volumes = [name+'-'+suffix for suffix in ('data', 'config', 'state')]
    gates = []
    def mark(message):
        gates.append(message)
        print('PASS '+message, flush=True)
    def info(container):
        return json.loads(run('docker', 'inspect', container))[0]
    def addresses(container):
        entry = info(container)['NetworkSettings']['Networks'][network]
        return entry['IPAddress'], entry['GlobalIPv6Address']
    def status():
        if not info(client)['State']['Running']:
            raise RuntimeError('Secured client exited; inspect its startup diagnostics below')
        return json.loads(run('docker', 'exec', client, 'cat', '/run/olo-toolgate/device-status.json'))
    def probe(container, host, port, protocol='tcp', confined=False):
        return run('docker', 'exec', container, *(DROP if confined else []),
                   'python', '-c', PROBE, host, str(port), protocol)
    try:
        run('docker', 'network', 'create', '--ipv6', '--subnet',
            'fd42:'+secrets.token_hex(2)+':'+secrets.token_hex(2)+'::/64', network)
        for volume in volumes:
            run('docker', 'volume', 'create', volume)
        run('docker', 'run', '-d', '--name', gateway, '--network', network, '--read-only',
            '--cap-drop=ALL', '--security-opt=no-new-privileges', '--memory=1536m', '--cpus=2',
            '--tmpfs', '/tmp:rw,noexec,nosuid,size=128m,uid=65532,gid=65532',
            '-e', 'TOOLGATE_CONTROL_ENDPOINT_CONTROL_URL=https://localhost:18453',
            '-e', 'TOOLGATE_CONTROL_ENDPOINT_GATEWAY_URL=https://localhost:18453',
            '-p', '127.0.0.1::8080', '-v', volumes[0]+':/data', quickstart)
        def origin():
            port = info(gateway)['NetworkSettings']['Ports']['8080/tcp'][0]['HostPort']
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
             'isolated Quickstart readiness')
        ipv4, _ = addresses(gateway)
        with tempfile.TemporaryDirectory(prefix='toolgate-public-ca-') as directory:
            ca = Path(directory)/'gateway.crt'
            run('docker', 'cp', gateway+':/data/keys/device-ca.crt', str(ca))
            # docker cp keeps Quickstart's 0600 mode; root without CAP_DAC_OVERRIDE cannot read it.
            ca.chmod(0o644)
            common = ['--network', network, '--security-opt=no-new-privileges',
                '--read-only', '--tmpfs', '/usr/local/lib/olo-toolgate:rw,exec,nosuid,size=32m,mode=755',
                '--tmpfs', '/run:rw,nosuid,noexec,size=8m,mode=755',
                '-e', 'TOOLGATE_GATEWAY_URLS=https://localhost:18453',
                '-e', 'TOOLGATE_SECURED_GATEWAY_IPV4='+ipv4,
                '-e', 'TOOLGATE_SECURED_GATEWAY_PORT=8443',
                '-e', 'TOOLGATE_GATEWAY_CA_FILE=/run/olo-toolgate-ca/gateway.crt',
                '--mount', 'type=bind,source='+str(ca)+',target=/run/olo-toolgate-ca/gateway.crt,readonly',
                '-v', volumes[1]+':/etc/olo-toolgate', '-v', volumes[2]+':/var/lib/olo-toolgate']
            # Fail closed without the bootstrap capability, without starting the client.
            denied = subprocess.run(['docker', 'run', '--rm', '--cap-drop=ALL', *common, image],
                                    text=True, capture_output=True, timeout=30)
            assert denied.returncode != 0 and 'Start with --cap-drop=ALL' in denied.stderr
            mark('missing firewall capability refuses startup')
            def start():
                run('docker', 'run', '-d', '--name', client, '--hostname', 'secured-device',
                    '--cap-drop=ALL', '--cap-add=NET_ADMIN', '--cap-add=SETPCAP', *common, image)
            start()
            pending = wait(lambda: (value if value.get('userCode') else None) if (value := status()) else None,
                           'automatic secured device enrollment')
            mark('automatic enrollment through the single permitted Gateway TLS port')
            password = run('docker', 'exec', gateway, 'cat', '/data/bootstrap-password')
            token = api('/api/quickstart/v1/login', dict(username='admin', password=password,
                        newPassword=secrets.token_urlsafe(32)))['accessToken']
            review = api('/api/control/v1/endpoint/enrollments/review?code='+pending['userCode'], token=token)
            assert review['keyFingerprint'] == pending['keyFingerprint']
            api('/api/control/v1/endpoint/enrollments/decision',
                dict(userCode=review['userCode'], keyFingerprint=review['keyFingerprint'], choice='APPROVE'), token)
            device_id = pending['deviceId']
            def active(after=0):
                row = api('/api/control/v1/endpoint/devices/'+device_id, token=token)
                return row if row['state'] == 'ACTIVE' and row.get('lastSeenUnixMs', 0) > after else None
            wait(active, 'secured device real mTLS check-in')
            mark('fingerprint approval and real native mTLS check-in')
            caps = run('docker', 'exec', client, 'python', '-c',
                "from pathlib import Path;s=Path('/proc/1/status').read_text();print(s)")
            assert all(int(line.split(':')[1].strip(), 16) == 0 for line in caps.splitlines() if line.startswith('Cap'))
            change = subprocess.run(['docker', 'exec', client, *DROP, 'nft', 'flush', 'ruleset'], capture_output=True)
            assert change.returncode != 0
            mark('service capabilities removed and child cannot change the firewall')
            run('docker', 'run', '-d', '--name', peer, '--network', network, '--cap-drop=ALL',
                '--security-opt=no-new-privileges', '--entrypoint', 'python', image, '-u', '-c', PEER)
            wait(lambda: 'READY' in run('docker', 'logs', peer), 'reachable IPv4/IPv6/UDP peer')
            peer4, peer6 = addresses(peer)
            assert probe(peer, peer4, 8123) == 'REACHABLE'
            assert probe(peer, peer6, 8123) == 'REACHABLE'
            assert probe(peer, peer4, 8124, 'udp') == 'REACHABLE'
            udp_before = run('docker', 'exec', peer, 'cat', '/tmp/udp-received')
            assert probe(peer, ipv4, 8080) == 'REACHABLE'
            for host, port, protocol in ((peer4, 8123, 'tcp'), (peer4, 8124, 'udp'),
                                         (peer6, 8123, 'tcp'), (ipv4, 8080, 'tcp'),
                                         ('1.1.1.1', 443, 'tcp'), ('127.0.0.11', 53, 'tcp')):
                assert probe(client, host, port, protocol, confined=True) == 'BLOCKED', (host, port, protocol)
            assert run('docker', 'exec', peer, 'cat', '/tmp/udp-received') == udp_before
            # A valid Docker DNS query must work on the peer and time out on the secured device.
            dns = "import socket,sys;h=sys.argv[1];q=b'\\x12\\x34\\x01\\x00\\x00\\x01\\x00\\x00\\x00\\x00\\x00\\x00'+b''.join(bytes([len(p)])+p.encode() for p in h.split('.'))+b'\\x00\\x00\\x01\\x00\\x01';s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);s.settimeout(2);s.sendto(q,('127.0.0.11',53));s.recvfrom(4096)"
            run('docker', 'exec', peer, 'python', '-c', dns, peer)
            dns_denied = subprocess.run(['docker', 'exec', client, *DROP, 'python', '-c', dns, peer], capture_output=True)
            assert dns_denied.returncode != 0
            mark('other destinations/Gateway ports/UDP/Docker DNS/IPv6 egress blocked')
            run('docker', 'exec', '-d', client, *DROP, 'python', '-u', '-c',
                PEER.replace('8123', '9000').replace('8124', '9001'))
            client4, client6 = addresses(client)
            wait(lambda: probe(client, '127.0.0.1', 9000, confined=True) == 'BLOCKED', 'loopback denied outside relay')
            # Verify listeners exist, so failures below prove the firewall rather than absent services.
            wait(lambda: '00000000:2328' in run('docker', 'exec', client, 'cat', '/proc/net/tcp'), 'inbound test listener')
            assert probe(peer, client4, 9000) == 'BLOCKED'
            assert probe(peer, client4, 9001, 'udp') == 'BLOCKED'
            assert probe(peer, client6, 9000) == 'BLOCKED'
            mark('unsolicited IPv4/IPv6 TCP and UDP ingress blocked despite live listeners')
            run('docker', 'stop', '-t', '25', client)
            run('docker', 'rm', client)
            start()
            wait(lambda: status().get('deviceId') == device_id and status().get('state') == 'ACTIVE',
                 'retained secured identity after recreation')
            assert probe(client, peer4, 8123, confined=True) == 'BLOCKED'
            mark('recreation retains device identity and reinstalls the firewall')
            # Only the owner switches power, from the host; the final report reaches Control.
            assert run('docker', 'exec', client, 'olo-toolgate-client', 'power', 'off') == 'off'
            wait(lambda: (api('/api/control/v1/endpoint/devices/'+device_id, token=token).get('report') or {}).get('power') == 'OFF',
                 'powered-off report')
            assert run('docker', 'exec', client, 'olo-toolgate-client', 'power', 'status') == 'off'
            after = int(time.time()*1000)
            assert run('docker', 'exec', client, 'olo-toolgate-client', 'power', 'on') == 'on'
            wait(lambda: active(after), 'check-ins resumed after power on')
            assert status().get('deviceId') == device_id
            mark('owner power switch through docker exec; power on resumes without re-enrollment')
            run('docker', 'pause', gateway)
            time.sleep(6)
            assert info(client)['State']['Running']
            after = int(time.time()*1000)
            run('docker', 'unpause', gateway)
            wait(lambda: active(after), 'fresh secured check-in after Gateway outage', timeout=360)
            mark('Gateway outage and automatic reconnection under confinement')
            output = ROOT/'build/secured_device'
            output.mkdir(parents=True, exist_ok=True)
            (output/'smoke.json').write_text(json.dumps(dict(image=image, quickstartImage=quickstart,
                gates=gates), indent=2)+'\n', encoding='utf-8')
    except Exception:
        for container in (client, gateway):
            result = subprocess.run(['docker', 'logs', '--tail', '30', container], capture_output=True, text=True)
            print(result.stdout+result.stderr, flush=True)
        raise
    finally:
        for container in (peer, client, gateway):
            subprocess.run(['docker', 'rm', '-f', container], cwd=ROOT, capture_output=True)
        for volume in volumes:
            subprocess.run(['docker', 'volume', 'rm', volume], cwd=ROOT, capture_output=True)
        subprocess.run(['docker', 'network', 'rm', network], cwd=ROOT, capture_output=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', default='olo-toolgate-linux-secured-device:module11')
    parser.add_argument('--quickstart-image', default='olo-toolgate-quickstart:module11')
    arguments = parser.parse_args()
    check(arguments.image, arguments.quickstart_image)
