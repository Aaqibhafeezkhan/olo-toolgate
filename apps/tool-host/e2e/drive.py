# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Runs a packaged tool through a real Tool Host: a fake Control and a fake upstream on loopback,
a permit signed with a throwaway key, and the .tgpkg, service profile and globals placed in the
content store under their digests. Usage: drive.py <tool-host binary> <package.tgpkg> <out dir>."""

import base64
import hashlib
import json
import os
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unicodedata
import urllib.request
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOST_BIN, PACKAGE, OUT = sys.argv[1], sys.argv[2], sys.argv[3]
FENCE = "fence-e2e"
seen = {"consume": [], "report": [], "upstream": []}


def canonical(value):
    """The descriptor canonical form for the JSON this script writes: NFC strings, sorted keys, no floats."""
    def nfc(v):
        if isinstance(v, str):
            return unicodedata.normalize("NFC", v)
        if isinstance(v, list):
            return [nfc(x) for x in v]
        if isinstance(v, dict):
            return {nfc(k): nfc(x) for k, x in v.items()}
        return v
    text = json.dumps(nfc(value), sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return "sha256:" + hashlib.sha256(text.encode()).hexdigest()


def b64url(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        path = self.path.removeprefix("/api/control/v1/access")
        if path == "/permits/consume":
            seen["consume"].append(body)
            self.reply(200, {"invocationId": body["invocationId"], "fencingToken": FENCE, "leaseExpiresAtUnixMs": 0})
        elif path == "/effects/report":
            seen["report"].append(body)
            self.reply(200, {})
        else:
            self.reply(404, {})

    def do_GET(self):
        seen["upstream"].append({"path": self.path})
        item = self.path.rsplit("/", 1)[-1]
        self.reply(200, {"id": item, "name": f"Item {item}"})


def serve():
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server.server_address[1]


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def main():
    work = tempfile.mkdtemp(prefix="tg-host-e2e-")
    store = os.path.join(work, "store")
    for kind in ("packages", "profiles", "globals"):
        os.makedirs(os.path.join(store, kind))

    data = open(PACKAGE, "rb").read()
    with zipfile.ZipFile(PACKAGE) as z:
        package_digest = "sha256:" + hashlib.sha256(z.read("CHECKSUMS.sha256")).hexdigest()
        manifest = json.loads(z.read("toolgate-package.json"))
    open(os.path.join(store, "packages", package_digest[7:] + ".tgpkg"), "wb").write(data)

    control_port, upstream_port = serve(), serve()
    tool = {t["descriptor"]["toolId"].rsplit("/", 1)[-1]: t for t in manifest["tools"]}
    items = tool["items.get"]["descriptor"]
    profile = {
        "profileId": "sp-e2e", "revision": 1, "packageId": manifest["id"],
        "destinations": [{"id": d["id"], "scheme": "https", "host": "127.0.0.1", "port": upstream_port,
                          "tls": {"spkiPins": []}, "mode": "BROKERED"} for d in items["destinations"]],
        "routes": items["routes"], "privateRanges": [],
    }
    globals_ = {"Greeting": "Hi"}
    documents = {}
    for kind, value in (("profiles", profile), ("globals", globals_)):
        digest = canonical(value)
        json.dump(value, open(os.path.join(store, kind, digest[7:] + ".json"), "w"))
        documents[kind] = digest

    key = os.path.join(work, "permit.key")
    subprocess.run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", key],
                   check=True, capture_output=True)
    public = os.path.join(work, "permit.pem")
    subprocess.run(["openssl", "pkey", "-in", key, "-pubout", "-out", public], check=True, capture_output=True)

    port = free_port()
    config = {
        "hostId": "host-e2e", "listen": f"127.0.0.1:{port}",
        "permitKeys": [{"kid": "e2e", "publicKeyPath": public}], "permitIssuer": "control",
        "storeDir": store, "workDir": os.path.join(work, "w"),
        "control": {"tenantId":"10000000-0000-4000-8000-000000000001","url": f"http://127.0.0.1:{control_port}", "developmentLoopbackHttp": True},
        "developmentLoopbackHttp": True,
    }
    config_path = os.path.join(work, "host.json")
    json.dump(config, open(config_path, "w"))
    log = open(os.path.join(OUT, "tool-host.log"), "w")
    host = subprocess.Popen([HOST_BIN], env={**os.environ, "TOOLGATE_TOOL_HOST_CONFIG": config_path}, stderr=log)
    try:
        for _ in range(100):
            try:
                urllib.request.urlopen(f"http://127.0.0.1:{port}/v1/health", timeout=1)
                break
            except OSError:
                time.sleep(0.1)
        else:
            sys.exit("e2e: the Tool Host did not start; see tool-host.log")

        def dispatch(name, arguments, n):
            t = tool[name]
            now = int(time.time() * 1000)
            runtime = next(r for r in manifest["runtimes"] if r["runtimeId"] == t["runtimeId"])
            artifact = next(a for a in manifest["artifacts"] if a["artifactId"] == t["artifactIds"][0])
            claims = {
                "jti": f"jti-{n}", "iss": "control", "aud": "host-e2e", "iat": now, "nbf": now, "exp": now + 20000,
                "nonce": f"nonce-{n}", "invocationId": f"inv-{n}", "attempt": 1,
                "identity": {"tenantId": "10000000-0000-4000-8000-000000000001", "mode": "HUMAN", "userId": "u1", "chain": [], "authorizationEpoch": 1, "claims": {}},
                "target": {
                    "toolId": t["descriptor"]["toolId"], "toolDescriptorDigest": t["toolDescriptorDigest"],
                    "packageDigest": package_digest, "artifactDigest": artifact["artifactDigest"],
                    "runtimeDigest": canonical(runtime), "globalsDigest": documents["globals"],
                    "serviceProfileDigest": documents["profiles"], "serviceProfileRevision": 1,
                    "deploymentBindingDigest": "sha256:" + "c" * 64, "deploymentBindingRevision": 1, "configRevision": 1,
                    "routes": [r["id"] for r in t["descriptor"]["routes"]],
                },
                "catalogRevision": "rev-1",
                "argumentsDigest": "sha256:" + hashlib.sha256(
                    json.dumps(arguments, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest(),
                "action": name, "resources": [], "effectSafety": "READ_ONLY", "requiresExecutionLedger": False,
                "approvalIds": [], "deadlineUnixMs": now + 20000,
            }
            header = b64url(json.dumps({"alg": "RS256", "typ": "toolgate-invocation-permit+jws", "kid": "e2e"}).encode())
            signed = f"{header}.{b64url(json.dumps(claims).encode())}"
            signature = subprocess.run(["openssl", "dgst", "-sha256", "-sign", key], input=signed.encode(),
                                       check=True, capture_output=True).stdout
            body = json.dumps({"permit": {"jws": f"{signed}.{b64url(signature)}"}, "arguments": arguments}).encode()
            request = urllib.request.Request(f"http://127.0.0.1:{port}/v1/dispatch", data=body, method="POST")
            lines = [json.loads(l) for l in urllib.request.urlopen(request, timeout=60).read().splitlines() if l.strip()]
            print(f"e2e: {name}: {json.dumps(lines)}")
            return lines

        def expect(lines, structured):
            if lines[0].get("kind") != "ADMITTED" or len(lines) != 2 or lines[1]["outcome"] != "COMPLETED" \
                    or lines[1]["result"].get("structuredContent") != structured:
                sys.exit(f"e2e: expected {structured}")

        expect(dispatch("greet", {"name": "Ada"}, 1), {"text": "Hi, Ada"})
        expect(dispatch("items.get", {"id": "7"}, 2), {"id": "7", "name": "Item 7"})
        if [u["path"] for u in seen["upstream"]] != ["/items/7"]:
            sys.exit(f"e2e: the upstream saw {seen['upstream']}")
        if [r["outcome"] for r in seen["report"]] != ["COMPLETED", "COMPLETED"] or len(seen["consume"]) != 2:
            sys.exit(f"e2e: Control saw {seen}")
    finally:
        host.terminate()
        host.wait()
    print("e2e: passed")


main()
