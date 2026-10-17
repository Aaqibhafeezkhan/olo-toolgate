# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Stateless Streamable HTTP transport with Java-compatible validation and SSE."""
from __future__ import annotations

import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

from .._json import dumpb, loads
from .core import Connection, error, http_status
from .errors import PARSE_ERROR, ProtocolError
from .identity import Rejected
from ..runtime.config import StartupRefused
from ..runtime.network import loopback


class StreamableHttpServer:
    def __init__(self, core, address, verifier=None, development=False):
        host, port = address
        if development and not loopback(host):
            raise StartupRefused("CONFIGURATION", "Development HTTP must bind to loopback")
        if not loopback(host) and verifier is None:
            raise StartupRefused("CONFIGURATION", "Non-loopback HTTP requires an identity verifier")
        self.core, self.verifier = core, verifier
        self.loopback = loopback(host)
        self.stopping = threading.Event()
        outer = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *args):
                pass

            def reply(self, status, body=None, headers=None):
                data = b"" if body is None else dumpb(body)
                self.send_response(status)
                for k, v in (headers or {}).items():
                    self.send_header(k, v)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def do_GET(self):
                self.reply(405, headers={"Allow": "POST"})

            def do_DELETE(self):
                self.reply(405, headers={"Allow": "POST"})

            def do_POST(self):
                self.connection.settimeout(15)
                if self.path != "/mcp":
                    self.reply(404)
                    return
                identity = None
                if outer.verifier:
                    auth = self.headers.get("Authorization", "")
                    try:
                        if not auth.startswith("Bearer "):
                            raise Rejected()
                        identity = outer.verifier.verify(auth[7:])
                    except Rejected:
                        self.reply(401, headers={"WWW-Authenticate": "Bearer"})
                        self.close_connection = True
                        return
                if outer.loopback:
                    try:
                        host_name = urlsplit("//" + self.headers.get("Host", "")).hostname or ""
                        origin = self.headers.get("Origin")
                        if not loopback(host_name) or origin and not loopback(urlsplit(origin).hostname or ""):
                            raise ValueError()
                    except ValueError:
                        self.reply(403)
                        self.close_connection = True
                        return
                try:
                    length = int(self.headers.get("Content-Length", "-1"))
                    if length < 0 or self.headers.get("Transfer-Encoding"):
                        self.reply(400)
                        self.close_connection = True
                        return
                    if length > 2*1024*1024:
                        self.reply(413)
                        self.close_connection = True
                        return
                    message = loads(self.rfile.read(length))
                except (ValueError, OSError):
                    self.reply(400, error(None, ProtocolError(PARSE_ERROR, "Parse error")))
                    return
                connection = Connection("http", "http:anonymous", identity)
                accepts_sse = "text/event-stream" in self.headers.get("Accept", "")
                params = message.get("params") if isinstance(message, dict) else None
                meta = params.get("_meta") if isinstance(params, dict) else None
                progress = isinstance(meta, dict) and "progressToken" in meta
                listening = isinstance(message, dict) and message.get("method") == "subscriptions/listen"
                started = False
                output_lock = threading.Lock()

                def send(frame):
                    nonlocal started
                    if not accepts_sse:
                        return
                    with output_lock:
                        if not started:
                            self.send_response(200)
                            self.send_header("Content-Type", "text/event-stream")
                            self.send_header("Cache-Control", "no-cache")
                            self.send_header("Connection", "close")
                            self.end_headers()
                            self.close_connection = True
                            started = True
                        self.wfile.write(b"event: message\ndata: " + dumpb(frame) + b"\n\n")
                        self.wfile.flush()
                try:
                    response = outer.core.handle(message, connection, self.headers.get, send)
                    if response is not None:
                        if started or accepts_sse and progress and "result" in response:
                            send(response)
                        else:
                            self.reply(http_status(response["error"]["code"]) if "error" in response else 200, response)
                    elif listening and started:
                        ident = message.get("id")
                        while not outer.stopping.wait(.5) and outer.core.subscribed(connection, ident):
                            with output_lock:
                                self.wfile.write(b": keepalive\n\n")
                                self.wfile.flush()
                    else:
                        if listening:
                            outer.core.unsubscribe(connection, message.get("id"))
                        self.reply(202)
                except (OSError, ValueError):
                    self.close_connection = True
                finally:
                    if listening:
                        outer.core.unsubscribe(connection, message.get("id"))

        self.server = ThreadingHTTPServer(address, Handler)
        self.thread = None

    @property
    def address(self):
        return self.server.server_address

    def start(self):
        self.thread = threading.Thread(target=self.server.serve_forever, name="toolgate-http", daemon=True)
        self.thread.start()
        return self

    def close(self):
        self.stopping.set()
        if self.thread:
            self.server.shutdown()
            self.thread.join(timeout=5)
        self.server.server_close()

    def __enter__(self):
        return self.start()

    def __exit__(self, *args):
        self.close()
