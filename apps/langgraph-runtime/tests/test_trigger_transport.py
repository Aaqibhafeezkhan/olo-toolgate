# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""HTTPS/WSS wire tests with ephemeral local certificates; no live Control admission is claimed."""
from contextlib import contextmanager
import http.server
import io
import json
import logging
from pathlib import Path
import shutil
import ssl
import subprocess
import tempfile
import threading
import unittest
from unittest.mock import patch

from websockets.sync.server import serve
from websockets.exceptions import ConnectionClosedOK

from toolgate_runtime.trigger_bridge import DispatchReceipt, ListenerFailure, PROTOCOL, Reason, Subscription
from toolgate_runtime.trigger_transport import ControlTriggers, TriggerListener
from toolgate_runtime.trigger_listener import main

SUB = Subscription("monitor", "ts-immutable-1")
CURSOR = "0000000000000001"


def subscription(uid=SUB.uid):
    return {"apiVersion": "toolgate.olo/v1", "kind": "TriggerSubscription",
            "metadata": {"name": SUB.name, "uid": uid}, "spec": {}, "status": {"phase": "Active"}}


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def do_GET(self): self.respond()
    def do_POST(self): self.respond()
    def respond(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        self.server.requests.append((self.command, self.path, self.headers.get("Authorization"), body))
        status, headers, value = self.server.reply(self.command, self.path, body)
        raw = value if isinstance(value, bytes) else json.dumps(value).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        for key, val in headers.items(): self.send_header(key, val)
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        try: self.wfile.write(raw)
        except (BrokenPipeError, ssl.SSLError): pass


@unittest.skipUnless(shutil.which("openssl"), "OpenSSL is required for ephemeral HTTPS/WSS certificates")
class WireTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.cert = Path(cls.temp.name) / "cert.pem"
        cls.key = Path(cls.temp.name) / "key.pem"
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-sha256", "-days", "1",
                        "-keyout", str(cls.key), "-out", str(cls.cert), "-subj", "/CN=localhost",
                        "-addext", "subjectAltName=DNS:localhost"], check=True, capture_output=True)
        cls.key.chmod(0o600)

    @classmethod
    def tearDownClass(cls): cls.temp.cleanup()

    def tls(self):
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(self.cert, self.key)
        return context

    @contextmanager
    def http(self, reply):
        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        server.daemon_threads = True
        server.requests, server.reply = [], reply
        server.socket = self.tls().wrap_socket(server.socket, server_side=True)
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        try: yield server, f"https://localhost:{server.server_port}"
        finally:
            server.shutdown(); server.server_close(); thread.join(3)
            self.assertFalse(thread.is_alive())

    def client(self, origin):
        return ControlTriggers(origin, lambda: "secret-bootstrap-token", ca_file=str(self.cert), timeout_seconds=3)

    def test_https_existing_apis_and_exact_admission_body_ignore_event_configuration(self):
        def reply(method, path, raw):
            if method == "GET": return 200, {}, subscription()
            if path.endswith("/tickets"): return 201, {}, {"ticket": "A" * 43, "expiresAtUnixMs": 123}
            return 202, {}, {"subscriptionUid": SUB.uid, "cursor": CURSOR, "admissionId": "dispatch-1",
                             "disposition": "ADMITTED", "runId": "run-1"}
        with self.http(reply) as (server, origin):
            client = self.client(origin)
            client.verify_subscription(SUB)
            self.assertEqual(client.ticket(), "A" * 43)
            receipt = client.admit(SUB.uid, CURSOR, timeout_seconds=2)
            self.assertEqual(receipt.run_id, "run-1")
            self.assertEqual(server.requests[-1][1], "/api/control/v1/workflow-dispatches:admit")
            self.assertEqual(json.loads(server.requests[-1][3]), {"subscriptionUid": SUB.uid, "cursor": CURSOR})
            self.assertTrue(all(r[2] == "Bearer secret-bootstrap-token" for r in server.requests))

    def test_missing_admission_auth_failures_and_malformed_receipts_fail_closed(self):
        replies = ((404, {}, {"detail": "not implemented secret"}), (401, {}, {"detail": "secret"}),
                   (503, {}, {}), (200, {}, {"subscriptionUid": SUB.uid}),
                   (202, {}, {"subscriptionUid": SUB.uid, "cursor": CURSOR, "admissionId": "d", "disposition": "ADMITTED"}),
                   (202, {}, {"subscriptionUid": SUB.uid, "cursor": CURSOR, "admissionId": "d", "disposition": "REJECTED", "workflowId": "foreign"}),
                   (202, {}, {"subscriptionUid": SUB.uid, "cursor": CURSOR, "admissionId": "d",
                              "disposition": "REJECTED", "runId": None}),
                   (200, {}, b'{"disposition":"ADMITTED","disposition":"REJECTED"}'))
        for response in replies:
            with self.subTest(response=response), self.http(lambda *args: response) as (_, origin):
                with self.assertRaises(ListenerFailure) as failure:
                    self.client(origin).admit(SUB.uid, CURSOR, timeout_seconds=2)
                self.assertNotIn("secret", str(failure.exception))

    def test_invalid_admission_coordinates_are_refused_before_transport(self):
        with self.http(lambda *args: (500, {}, {})) as (server, origin):
            client = self.client(origin)
            for uid, cursor in (("", CURSOR), (SUB.uid, "not-a-cursor")):
                with self.subTest(uid=uid, cursor=cursor), self.assertRaises(ValueError):
                    client.admit(uid, cursor, timeout_seconds=2)
            self.assertFalse(server.requests)

    def test_https_redirect_never_forwards_credentials(self):
        with self.http(lambda *args: (200, {}, {})) as (target, target_origin):
            with self.http(lambda *args: (302, {"Location": target_origin + "/capture"}, {})) as (source, origin):
                with self.assertRaises(ListenerFailure): self.client(origin).ticket()
                self.assertEqual(len(source.requests), 1)
                self.assertFalse(target.requests)

    def test_uid_recreation_obsolete_subscription_and_inactive_state_are_refused(self):
        for value in (subscription("ts-new"), dict(subscription(), apiVersion="obsolete"),
                      dict(subscription(), status={"phase": "Suspended"})):
            with self.http(lambda *args: (200, {}, value)) as (server, origin):
                with self.assertRaisesRegex(ListenerFailure, "^SUBSCRIPTION_CHANGED$"):
                    self.client(origin).verify_subscription(SUB)
                self.assertEqual(len(server.requests), 1)

    def test_untrusted_certificate_and_non_https_origins_are_refused(self):
        with self.http(lambda *args: (200, {}, {})) as (_, origin):
            with self.assertRaises(ListenerFailure): ControlTriggers(origin, lambda: "secret", timeout_seconds=2).ticket()
        for origin in ("http://localhost", "https://user:secret@localhost", "https://localhost/path", "https://localhost?token=secret"):
            with self.assertRaises(ValueError): self.client(origin)

    def test_response_bounds_and_ticket_format_are_checked(self):
        for value in (b"x" * 65537, {"ticket": "malformed"}, {"ticket": "A" * 43 + "\r\n"}):
            with self.http(lambda *args: (200, {}, value)) as (_, origin):
                with self.assertRaises(ListenerFailure): self.client(origin).ticket()

    @contextmanager
    def websocket(self, handler, *, protocols=None, process_request=None):
        logger = logging.Logger("test.websocket"); logger.disabled = True
        with serve(handler, "127.0.0.1", 0, ssl=self.tls(), subprotocols=protocols or [PROTOCOL],
                   process_request=process_request, logger=logger, open_timeout=3, close_timeout=1) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
            try: yield server, f"https://localhost:{server.socket.getsockname()[1]}"
            finally:
                server.shutdown(); thread.join(3)
                self.assertFalse(thread.is_alive())

    def test_wss_subscription_ack_credit_and_fresh_ticket_on_each_reconnect(self):
        seen, offered, closed, errors, finished = [], [], [], [], threading.Event()
        def handshake(connection, request): offered.append(request.headers["Sec-WebSocket-Protocol"])
        def handler(ws):
            try:
                frames = [json.loads(ws.recv(timeout=3))]
                seen.append(frames)
                ws.send(json.dumps({"type": "subscribed", "subscription": SUB.name}))
                ws.send(json.dumps({"type": "event", "subscription": SUB.name, "cursor": CURSOR,
                                    "event": {"workflowId": "forged", "runAs": "forged"}}))
                frames.append(json.loads(ws.recv(timeout=3)))
                frames.append(json.loads(ws.recv(timeout=3)))
                # The real stream stays open. Let the max-events client close it,
                # rather than racing its final send with a server-initiated close.
                try:
                    ws.recv(timeout=3)
                    errors.append("unexpected additional frame")
                except ConnectionClosedOK:
                    closed.append(True)
                if len(closed) == 2: finished.set()
            except Exception as failure:
                errors.append(type(failure).__name__)
                finished.set()
        with self.websocket(handler, process_request=handshake) as (_, origin):
            client = self.client(origin); checks, admissions, tickets = [], [], iter(("A" * 43, "B" * 43))
            client.verify_subscription = lambda sub: checks.append(sub.uid)
            client.ticket = lambda: next(tickets)
            def admit(uid, cursor, **kw):
                admissions.append((uid, cursor))
                return DispatchReceipt(uid, cursor, "dispatch-1", "ADMITTED" if len(admissions) == 1 else "DUPLICATE", "run-1")
            client.admit = admit
            for _ in range(2):
                count, receipt = TriggerListener(client, SUB).listen_once(max_events=1)
                self.assertEqual(count, 1); self.assertEqual(receipt.run_id, "run-1")
            self.assertTrue(finished.wait(3))
            self.assertEqual(errors, [])
            self.assertEqual(len(closed), 2)
            self.assertEqual(checks, [SUB.uid, SUB.uid])
            self.assertEqual(admissions, [(SUB.uid, CURSOR), (SUB.uid, CURSOR)])
            self.assertIn("ticket." + "A" * 43, offered[0]); self.assertIn("ticket." + "B" * 43, offered[1])
            for frames in seen:
                self.assertEqual(frames[0], {"type": "subscribe", "subscription": SUB.name})
                self.assertNotIn("resumeAfter", frames[0])
                self.assertEqual(frames[1], {"type": "ack", "subscription": SUB.name, "cursor": CURSOR})
                self.assertEqual(frames[2], {"type": "credit", "n": 1})

    def test_ticket_subprotocol_is_never_accepted_as_the_selected_protocol(self):
        received = []
        def handler(ws):
            try: received.append(ws.recv(timeout=3))
            except Exception: pass
        with self.websocket(handler, protocols=["ticket." + "A" * 43]) as (_, origin):
            client = self.client(origin)
            client.verify_subscription = lambda sub: None
            client.ticket = lambda: "A" * 43
            with self.assertRaisesRegex(ListenerFailure, "^CONNECTION_LOST$"):
                TriggerListener(client, SUB).listen_once(max_events=1)
            self.assertFalse(received)

    def test_wss_redirect_is_refused_without_ticket_forwarding_or_logging(self):
        with self.http(lambda *args: (200, {}, {})) as (target, target_origin):
            def redirect(connection, request):
                response = connection.respond(302, "Moved")
                response.headers["Location"] = target_origin.replace("https:", "wss:") + "/capture"
                return response
            with self.websocket(lambda ws: None, process_request=redirect) as (_, origin):
                client = self.client(origin)
                output = io.StringIO(); handler = logging.StreamHandler(output)
                root = logging.getLogger(); before = root.level; root.setLevel(logging.DEBUG); root.addHandler(handler)
                try:
                    with self.assertRaises(Exception):
                        with client.connect("A" * 43): self.fail("Redirect must not succeed")
                finally:
                    root.removeHandler(handler); root.setLevel(before)
                self.assertFalse(target.requests)
                self.assertNotIn("A" * 43, output.getvalue())


class ListenerTests(unittest.TestCase):
    def test_uid_is_checked_before_ticket_or_connection(self):
        class Recreated:
            timeout = 1
            def verify_subscription(self, sub): raise ListenerFailure(Reason.SUBSCRIPTION_CHANGED)
            def ticket(self): raise AssertionError("No ticket after UID mismatch")
        with self.assertRaisesRegex(ListenerFailure, "^SUBSCRIPTION_CHANGED$"):
            TriggerListener(Recreated(), SUB).listen_once(max_events=1)

    def test_cli_reports_admission_reference_without_claiming_workflow_completion(self):
        receipt = DispatchReceipt(SUB.uid, CURSOR, "dispatch-1", "ADMITTED", "run-1")
        with patch("toolgate_runtime.trigger_listener.TriggerListener.listen_once", return_value=(1, receipt)), \
             patch("sys.stdout", new_callable=io.StringIO) as output:
            result = main(["--control-origin", "https://localhost", "--subscription", SUB.name,
                           "--subscription-uid", SUB.uid, "--max-events", "1"])
        self.assertEqual(result, 0)
        self.assertEqual(json.loads(output.getvalue()), {"processed": 1, "admissionId": "dispatch-1",
                                                      "disposition": "ADMITTED", "runId": "run-1"})

    def test_cli_error_output_never_contains_credentials_or_remote_body(self):
        with patch.dict("os.environ", {"TEST_TOKEN": "secret-bootstrap-token"}), \
             patch("toolgate_runtime.trigger_listener.TriggerListener.listen_once", side_effect=RuntimeError("secret-bootstrap-token")), \
             patch("sys.stdout", new_callable=io.StringIO) as output:
            result = main(["--control-origin", "https://localhost", "--subscription", SUB.name,
                           "--subscription-uid", SUB.uid, "--token-env", "TEST_TOKEN", "--max-events", "1"])
        self.assertEqual(result, 2)
        self.assertEqual(json.loads(output.getvalue()), {"error": "BOOTSTRAP_FAILED"})


if __name__ == "__main__": unittest.main()
