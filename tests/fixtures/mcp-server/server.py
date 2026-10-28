# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""A scriptable MCP server (Streamable HTTP) for MCP proxy tests. Python standard library only.

In process:
    with FixtureServer(script) as server:
        server.url            # http(s)://127.0.0.1:<port>/mcp
        server.update(...)    # change the script while it runs
        server.requests       # what it received

As a process (an existing Python image in composed tests):
    python server.py --port 8443 --script script.json [--cert cert.pem --key key.pem] [--admin-token TOKEN]
With --admin-token, PUT /_fixture/script (Authorization: Bearer TOKEN) replaces the script and GET /_fixture/requests
returns the request log, so a composed test can change the upstream between scans.

The script is a JSON object (every member optional):
    serverInfo        {"name", "version", "title"?}
    protocolVersion   the version it answers to initialize
    auth              {"header": "Authorization", "value": "Bearer x"}: other requests get 401
    reply             "json" (default) or "sse": how POST replies are framed
    pageSize          tools per tools/list page (default: all on one page)
    listPadding       characters added to every listed description (oversize tools/list)
    trickle           {"bytes": n, "interval": seconds}: tools/list replies are sent n bytes at a time, without end
    listChanged       true: a GET stream sends notifications/tools/list_changed once, and the capability is announced
    tools             list of {"definition": {...MCP tool...}, "result"?: {...CallToolResult...},
                               "error"?: {"code", "message"}, "delay"?: seconds, "notifications"?: [method, ...],
                               "keepOpen"?: seconds, "raw"?: "bytes sent instead of a JSON-RPC response"}
A tool without "result" or "error" echoes its arguments as structured content.
"""
import argparse
import json
import ssl
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PATH = '/mcp'
DEFAULT_SCRIPT = {
    'serverInfo': {'name': 'fixture-mcp', 'version': '1.0.0'},
    'protocolVersion': '2025-11-25',
    'tools': [{'definition': {'name': 'echo', 'description': 'Echo the arguments.',
                              'inputSchema': {'type': 'object', 'properties': {'text': {'type': 'string'}}}}}],
}


class FixtureServer:
    def __init__(self, script=None, host='127.0.0.1', port=0, cert=None, key=None, admin_token=None):
        self._lock = threading.Lock()
        self._script = json.loads(json.dumps(script if script is not None else DEFAULT_SCRIPT))
        self.requests = []
        self.sessions = set()
        self.admin_token = admin_token
        self.httpd = ThreadingHTTPServer((host, port), _handler(self))
        self.httpd.daemon_threads = True
        self.scheme = 'http'
        if cert:
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            context.minimum_version = ssl.TLSVersion.TLSv1_2
            context.load_cert_chain(cert, key)
            self.httpd.socket = context.wrap_socket(self.httpd.socket, server_side=True)
            self.scheme = 'https'
        self._thread = None

    @property
    def url(self):
        host, port = self.httpd.server_address[:2]
        return f'{self.scheme}://{host}:{port}{PATH}'

    @property
    def script(self):
        with self._lock:
            return json.loads(json.dumps(self._script))

    def update(self, **members):
        """Replace top-level script members, for example update(tools=[...]) or update(serverInfo={...})."""
        with self._lock:
            self._script.update(json.loads(json.dumps(members)))

    def record(self, entry):
        with self._lock:
            self.requests.append(entry)

    def start(self):
        self._thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self._thread.start()
        return self

    def stop(self):
        self.httpd.shutdown()
        self.httpd.server_close()

    def __enter__(self):
        return self.start()

    def __exit__(self, *_):
        self.stop()


def _handler(server):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'

        def log_message(self, *_):
            pass

        # --- transport -------------------------------------------------------------------------------------------
        def do_POST(self):
            self._session = None  # one handler serves every request of a kept-alive connection
            if self.path == '/_fixture/script':
                return self._admin_script()
            if self.path != PATH:
                return self._status(404)
            body = self.rfile.read(int(self.headers.get('Content-Length') or 0))
            try:
                message = json.loads(body)
            except ValueError:
                return self._json(400, _error(None, -32700, 'parse error'))
            server.record({'method': message.get('method'), 'params': message.get('params'), 'headers': dict(self.headers)})
            script = server.script
            if not self._authorized(script):
                return self._status(401)
            if 'id' not in message:
                return self._status(202)  # a notification or a response
            method = message.get('method')
            if method != 'initialize' and self.headers.get('Mcp-Session-Id') not in server.sessions:
                return self._json(404 if self.headers.get('Mcp-Session-Id') else 400, _error(message['id'], -32600, 'no session'))
            handler = {'initialize': self._initialize, 'ping': lambda s, m: (_result(m['id'], {}), None),
                       'tools/list': self._list, 'tools/call': self._call}.get(method)
            if handler is None:
                return self._reply(script, _error(message['id'], -32601, f'unknown method {method}'))
            response, tool = handler(script, message)
            if response is None:
                return
            self._reply(script, response, tool)

        def do_GET(self):
            if self.path == '/_fixture/requests':
                if not self._admin():
                    return self._status(401)
                return self._json(200, server.requests)
            if self.path != PATH:
                return self._status(404)
            script = server.script
            if not self._authorized(script):
                return self._status(401)
            if not script.get('listChanged'):
                return self._status(405)
            self._sse_head()
            self._event({'jsonrpc': '2.0', 'method': 'notifications/tools/list_changed'})
            self._end_chunks()

        def do_PUT(self):
            if self.path == '/_fixture/script':
                return self._admin_script()
            self._status(405)

        def do_DELETE(self):
            server.sessions.discard(self.headers.get('Mcp-Session-Id'))
            self._status(200)

        # --- methods ---------------------------------------------------------------------------------------------
        def _initialize(self, script, message):
            session = uuid.uuid4().hex
            server.sessions.add(session)
            self._session = session
            capabilities = {'tools': {'listChanged': bool(script.get('listChanged'))}}
            return _result(message['id'], {'protocolVersion': script.get('protocolVersion', '2025-11-25'),
                                           'capabilities': capabilities, 'serverInfo': script.get('serverInfo', {})}), None

        def _list(self, script, message):
            tools = [dict(t['definition']) for t in script.get('tools', [])]
            padding = script.get('listPadding')
            if padding:
                for tool in tools:
                    tool['description'] = tool.get('description', '') + 'x' * padding
            start = int((message.get('params') or {}).get('cursor') or 0)
            size = script.get('pageSize') or max(len(tools), 1)
            page = {'tools': tools[start:start + size]}
            if start + size < len(tools):
                page['nextCursor'] = str(start + size)
            response = _result(message['id'], page)
            trickle = script.get('trickle')
            if trickle:
                self._trickle(json.dumps(response).encode(), trickle)
                return None, None
            return response, None

        def _call(self, script, message):
            params = message.get('params') or {}
            tool = next((t for t in script.get('tools', []) if t['definition'].get('name') == params.get('name')), None)
            if tool is None:
                return _error(message['id'], -32602, f'unknown tool {params.get("name")}'), None
            if tool.get('delay'):
                time.sleep(tool['delay'])
            if 'raw' in tool:
                body = tool['raw'].encode()
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return None, None
            if 'error' in tool:
                return _error(message['id'], tool['error'].get('code', -32603), tool['error'].get('message', 'error')), tool
            result = tool.get('result') or {'content': [{'type': 'text', 'text': json.dumps(params.get('arguments') or {})}],
                                            'structuredContent': params.get('arguments') or {}}
            return _result(message['id'], result), tool

        # --- framing ---------------------------------------------------------------------------------------------
        def _reply(self, script, response, tool=None):
            tool = tool or {}
            if script.get('reply', 'json') == 'json' and not tool.get('notifications') and not tool.get('keepOpen'):
                return self._json(200, response)
            self._sse_head()
            for method in tool.get('notifications', []):
                self._event({'jsonrpc': '2.0', 'method': method, 'params': {}})
            self._event(response)
            if tool.get('keepOpen'):
                time.sleep(tool['keepOpen'])
            self._end_chunks()

        def _headers(self):
            if getattr(self, '_session', None):
                self.send_header('Mcp-Session-Id', self._session)

        def _json(self, status, value):
            body = json.dumps(value).encode()
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self._headers()
            self.end_headers()
            self.wfile.write(body)

        def _status(self, status):
            self.send_response(status)
            self.send_header('Content-Length', '0')
            self.end_headers()

        def _sse_head(self):
            self.send_response(200)
            self.send_header('Content-Type', 'text/event-stream')
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Transfer-Encoding', 'chunked')
            self._headers()
            self.end_headers()

        def _chunk(self, data):
            self.wfile.write(f'{len(data):x}\r\n'.encode() + data + b'\r\n')
            self.wfile.flush()

        def _event(self, message):
            self._chunk(b'event: message\ndata: ' + json.dumps(message).encode() + b'\n\n')

        def _end_chunks(self):
            self.wfile.write(b'0\r\n\r\n')
            self.wfile.flush()

        def _trickle(self, body, trickle):
            """Sends the reply a few bytes at a time and never finishes it: the client's deadline must end it."""
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Transfer-Encoding', 'chunked')
            self._headers()
            self.end_headers()
            step, interval, at = max(1, trickle.get('bytes', 1)), trickle.get('interval', 0.5), 0
            try:
                while True:
                    self._chunk(body[at:at + step] or b' ')
                    at += step
                    time.sleep(interval)
            except OSError:
                pass

        # --- auth and admin --------------------------------------------------------------------------------------
        def _authorized(self, script):
            auth = script.get('auth')
            return not auth or self.headers.get(auth['header']) == auth['value']

        def _admin(self):
            return bool(server.admin_token) and self.headers.get('Authorization') == f'Bearer {server.admin_token}'

        def _admin_script(self):
            body = self.rfile.read(int(self.headers.get('Content-Length') or 0))
            if not self._admin():
                return self._status(401)
            with server._lock:
                server._script = json.loads(body)
            self._status(204)

    return Handler


def _result(request_id, result):
    return {'jsonrpc': '2.0', 'id': request_id, 'result': result}


def _error(request_id, code, message):
    return {'jsonrpc': '2.0', 'id': request_id, 'error': {'code': code, 'message': message}}


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--host', default='0.0.0.0')
    parser.add_argument('--port', type=int, default=8443)
    parser.add_argument('--script')
    parser.add_argument('--cert')
    parser.add_argument('--key')
    parser.add_argument('--admin-token')
    args = parser.parse_args()
    script = json.load(open(args.script, encoding='utf-8')) if args.script else None
    server = FixtureServer(script, args.host, args.port, args.cert, args.key, args.admin_token)
    print(f'fixture MCP server on {server.url}', flush=True)
    server.httpd.serve_forever()


if __name__ == '__main__':
    main()
