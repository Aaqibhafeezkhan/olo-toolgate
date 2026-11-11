# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Owned S22 network fixture: drop links, never alter authority payloads.

The fixture owns this fresh composition's TLS keys. It re-establishes the exact
owned device peer at the real backend; it does not use certificate assertion
headers. No deployment or untrusted-CA qualification is inferred from this hop.
"""
from collections import deque
import hashlib
import http.client
import http.server
import json
from pathlib import Path
import re
import ssl
import sys
import threading
import time


# This relay connects only to its own Control/Gateway listener and accepts
# origin-form API targets, never proxy targets or authority overrides.
API_TARGET = re.compile(r"/api/[A-Za-z0-9._~!$&'()*+,;=:@%/?-]*", re.ASCII)
HEADER_NAME = re.compile(r"[!#$%&'*+.^_`|~0-9A-Za-z-]+", re.ASCII)
HEADER_VALUE = re.compile(r"[\t\x20-\x7e\x80-\xff]*")
HOP_HEADERS = frozenset(("connection", "keep-alive", "proxy-connection",
                         "transfer-encoding", "proxy-authorization",
                         "proxy-authenticate", "te", "trailer", "upgrade"))


def request_target(path):
    if not API_TARGET.fullmatch(path):
        raise ValueError("Fixture requires an origin-form API request target")
    return path


def relay_headers(headers, *, exclude=()):
    omitted = HOP_HEADERS.union(exclude)
    for key, value in headers:
        if not HEADER_NAME.fullmatch(key) or not HEADER_VALUE.fullmatch(value):
            raise ValueError("Invalid fixture HTTP header")
        if key.lower() == "connection":
            omitted = omitted.union(part.strip().lower() for part in value.split(","))
    return [(key, value) for key, value in headers if key.lower() not in omitted]


class LinkFixture(http.server.ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, supervisor):
        self.owner = supervisor
        self.directory = supervisor.DATA / "s22-link"
        self.directory.mkdir(mode=0o700, exist_ok=True)
        self.directory.chmod(0o700)
        self.mode_path = self.directory / "mode.json"
        if not self.mode_path.exists():
            supervisor.atomic(self.mode_path, json.dumps(dict(
                version=1, generation=0, mode="HEALTHY", hostId="tool-host-remote")))
        self.events = deque(maxlen=256)
        self.lock = threading.Lock()
        self.capacity = threading.BoundedSemaphore(32)
        self.clients = {}
        self.context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        self.context.load_cert_chain(str(supervisor.trust("server.crt")),
                                     str(supervisor.trust("server.key")))
        packet = supervisor.strict((supervisor.DATA / "tenant-identity/publication.json").read_bytes())
        certificates = [key["certificatePem"] for publication in packet["publications"]
                        if publication["tenantId"] == supervisor.TENANT
                        for key in publication["keys"]
                        if key["purpose"] == "DEVICE_CA" and key["state"] in ("ACTIVE", "READ_ONLY")]
        if not certificates:
            raise ValueError("Owned current tenant device trust required")
        self.context.load_verify_locations(cadata="\n".join(certificates))
        self.context.verify_mode = ssl.CERT_OPTIONAL
        super().__init__(("127.0.0.1", 18443), Handler)

    def get_request(self):
        raw, address = self.socket.accept()
        raw.settimeout(10)
        try:
            return self.context.wrap_socket(raw, server_side=True), address
        except BaseException:
            raw.close()
            raise

    def process_request(self, request, address):
        if not self.capacity.acquire(False):
            request.close()
            return
        try:
            super().process_request(request, address)
        except BaseException:
            self.capacity.release()
            raise

    def process_request_thread(self, request, address):
        try:
            super().process_request_thread(request, address)
        finally:
            self.capacity.release()

    def mode(self):
        try:
            mode = self.owner.strict(self.mode_path.read_bytes())
            if (set(mode) != {"version", "generation", "mode", "hostId"}
                    or mode["version"] != 1 or type(mode["generation"]) is not int
                    or mode["generation"] < 0 or mode["mode"] not in ("HEALTHY", "KILLS", "ALL")
                    or mode["hostId"] not in ("tool-host-local", "tool-host-remote")):
                raise ValueError()
            return mode
        except (OSError, ValueError, TypeError):
            return dict(generation=-1, mode="ALL", hostId="tool-host-remote")

    def blocked(self, host, path, method):
        mode = self.mode()
        blocked = host == mode["hostId"] and (mode["mode"] == "ALL"
                    or mode["mode"] == "KILLS" and method == "GET" and path.startswith("/api/control/v1/tool-hosts/kills"))
        return blocked, mode

    def record(self, host, path, outcome, mode, status=None):
        with self.lock:
            self.events.append(dict(host=host, path=path.split("?", 1)[0], outcome=outcome,
                                    generation=mode["generation"], mode=mode["mode"],
                                    status=status, atUnixMs=int(time.time()*1000)))
            self.owner.atomic(self.directory / "stats.json",
                              json.dumps(dict(version=1, events=list(self.events))))

    def peer(self, der):
        if not der:
            context = ssl.create_default_context(cafile=str(self.owner.DATA / "keys/device-ca.crt"))
            return None, context
        from cryptography import x509
        from cryptography.hazmat.primitives.serialization import Encoding
        fingerprint = hashlib.sha256(der).hexdigest()
        with self.lock:
            if fingerprint in self.clients:
                return self.clients[fingerprint]
            for name in ("tool-host", "tool-host-remote"):
                directory = self.owner.DATA / "state" / name
                path = directory / "device.json"
                if not path.exists():
                    continue
                journal = self.owner.strict(path.read_bytes())
                identity = journal.get("identity")
                if not identity:
                    continue
                if journal["formatVersion"] != 3 or identity["tenantId"] != self.owner.TENANT:
                    raise ValueError("Current owned host identity required")
                certificate = x509.load_pem_x509_certificate(identity["certificatePem"].encode())
                if hashlib.sha256(certificate.public_bytes(Encoding.DER)).hexdigest() != fingerprint:
                    continue
                chain = self.directory / (fingerprint + ".pem")
                self.owner.atomic(chain, identity["certificatePem"] + "\n" + identity["issuerCertificatePem"])
                chain.chmod(0o600)
                context = ssl.create_default_context(cafile=str(self.owner.DATA / "keys/device-ca.crt"))
                context.load_cert_chain(str(chain), str(directory / "device-key.pem"))
                value = identity["deviceId"], context
                self.clients[fingerprint] = value
                return value
        raise ValueError("Only the two fixture-owned device peers may be relayed")


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_):
        pass

    def do_GET(self):
        self.forward()

    def do_POST(self):
        self.forward()

    def forward(self):
        host = None
        upstream = None
        try:
            target = request_target(self.path)
            host, context = self.server.peer(self.connection.getpeercert(binary_form=True))
            blocked, mode = self.server.blocked(host, target, self.command)
            if blocked:
                self.server.record(host, target, "DROPPED", mode)
                self.close_connection = True
                self.connection.close()
                return
            lengths = self.headers.get_all("Content-Length") or []
            if (self.headers.get_all("Transfer-Encoding") or len(lengths) > 1
                    or lengths and (not lengths[0].isdigit() or int(lengths[0]) > 16*1024*1024)):
                raise ValueError("Bounded unambiguous fixture request required")
            body = self.rfile.read(int(lengths[0])) if lengths else None
            if lengths and len(body) != int(lengths[0]):
                raise ValueError("Incomplete fixture request body")
            upstream = http.client.HTTPSConnection("127.0.0.1", 8443, context=context, timeout=7)
            upstream.putrequest(self.command, target, skip_accept_encoding=True)
            for key, value in relay_headers(self.headers.items(), exclude=("host", "content-length")):
                upstream.putheader(key, value)
            if lengths:
                upstream.putheader("Content-Length", str(len(body)))
            upstream.putheader("Connection", "close")
            upstream.endheaders(body)
            response = upstream.getresponse()
            payload = response.read(128*1024*1024+1)
            if len(payload) > 128*1024*1024:
                raise ValueError("Owned fixture response budget exceeded")
            blocked, mode = self.server.blocked(host, target, self.command)
            if blocked:
                self.server.record(host, target, "DROPPED", mode)
                self.close_connection = True
                self.connection.close()
                return
            headers = relay_headers(response.getheaders(), exclude=("content-length",))
            self.send_response(response.status)
            for key, value in headers:
                self.send_header(key, value)
            self.send_header("Content-Length", str(len(payload)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(payload)
            self.wfile.flush()
            self.server.record(host, target, "FORWARDED", mode, response.status)
        except (OSError, ValueError, KeyError, http.client.HTTPException):
            self.close_connection = True
            self.connection.close()
        finally:
            if upstream:
                upstream.close()
            self.close_connection = True



def configure(supervisor, original):
    """Use current supported configuration to qualify real dispatch mTLS."""
    environment=original()
    import datetime
    import os
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID
    import ipaddress
    grace = os.environ.get("TOOLGATE_S22_CONTROL_LOSS_GRACE_MS")
    if grace is not None and (not grace.isdigit() or not 1000 <= int(grace) <= 120000):
        raise ValueError("Bounded current Host Control-loss grace required")
    directory = supervisor.DATA / "s22-dispatch"
    directory.mkdir(mode=0o700, exist_ok=True)
    directory.chmod(0o700)
    # Separate fixture-only CA; no device/operator/fleet signing keys are reused.
    ca_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    now = datetime.datetime.now(datetime.timezone.utc)
    ca_name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Owned S22 dispatch CA")])
    ca = (x509.CertificateBuilder().subject_name(ca_name).issuer_name(ca_name)
          .public_key(ca_key.public_key()).serial_number(x509.random_serial_number())
          .not_valid_before(now-datetime.timedelta(minutes=1)).not_valid_after(now+datetime.timedelta(days=1))
          .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
          .add_extension(x509.KeyUsage(False,False,False,False,False,True,True,False,False), critical=True)
          .sign(ca_key, hashes.SHA256()))
    def save(name, value):
        path = directory / name
        supervisor.atomic(path, value)
        path.chmod(0o600)
        return str(path)
    ca_path = save("ca.pem", ca.public_bytes(serialization.Encoding.PEM).decode())
    def identity(name, purpose):
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        cert = (x509.CertificateBuilder().subject_name(x509.Name([
                    x509.NameAttribute(NameOID.COMMON_NAME, name),
                    x509.NameAttribute(NameOID.ORGANIZATIONAL_UNIT_NAME, supervisor.TENANT)]))
                .issuer_name(ca.subject).public_key(key.public_key())
                .serial_number(x509.random_serial_number())
                .not_valid_before(now-datetime.timedelta(minutes=1)).not_valid_after(now+datetime.timedelta(days=1))
                .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
                .add_extension(x509.ExtendedKeyUsage([purpose]), critical=True)
                .add_extension(x509.KeyUsage(True,False,False,False,False,False,False,False,False), critical=True)
                .add_extension(x509.SubjectAlternativeName([x509.DNSName("localhost"),
                    x509.IPAddress(ipaddress.ip_address("127.0.0.1"))]), critical=False)
                .sign(ca_key, hashes.SHA256()))
        return (save(name+".pem",cert.public_bytes(serialization.Encoding.PEM).decode()),
                save(name+".key",key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,
                                                   serialization.NoEncryption()).decode()))
    server, server_key = identity("host", ExtendedKeyUsageOID.SERVER_AUTH)
    client, client_key = identity("gateway", ExtendedKeyUsageOID.CLIENT_AUTH)
    for name in ("tool-host", "tool-host-remote"):
        path = supervisor.DATA / "run" / (name+".json")
        host = supervisor.strict(path.read_bytes())
        if grace is not None:
            host.setdefault("limits", {})["controlLossGraceMs"] = int(grace)
        if name == "tool-host":
            host["developmentDispatchHttp"] = False
            host["dispatchTls"] = dict(certificatePath=server,privateKeyPath=server_key,
                                       gatewayCaPath=ca_path,gatewayCertificates=[client])
        supervisor.atomic(path,json.dumps(host))
    path = supervisor.DATA / "run/gateway.json"
    gateway = supervisor.strict(path.read_bytes())
    route = gateway["toolHosts"][0]
    assert route["hostId"] == supervisor.TOOL_HOST
    route.update(url="https://localhost:8095",developmentLoopbackHttp=False,
                 tls=dict(clientCertificatePath=client,clientKeyPath=client_key,
                          caCertificatePath=ca_path,serverCertificates=[server]))
    supervisor.atomic(path,json.dumps(gateway))
    return environment


if __name__ == "__main__":
    sys.path.insert(0, "/opt/quickstart")
    import supervisor
    original = supervisor.configure
    supervisor.configure = lambda: configure(supervisor, original)
    def bridges():
        server = LinkFixture(supervisor)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        return [server]
    supervisor.tls_bridges = bridges
    supervisor.main()
