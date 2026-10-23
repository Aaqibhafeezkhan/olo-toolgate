# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""The fixture MCP server (tests/fixtures/mcp-server) behaves as the MCP proxy tests script it to."""
import http.client
import importlib.util
import json
import socket
import time
import unittest
import urllib.parse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('mcp_fixture_server', ROOT/'tests/fixtures/mcp-server/server.py')
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)

ACCEPT = 'application/json, text/event-stream'


def tool(name, **extra):
    return {'definition': {'name': name, 'description': f'{name} tool', 'inputSchema': {'type': 'object'}}, **extra}


class Client:
    def __init__(self, server, headers=None, timeout=5):
        url = urllib.parse.urlparse(server.url)
        self.connection = http.client.HTTPConnection(url.hostname, url.port, timeout=timeout)
        self.path, self.headers, self.session, self.next_id = url.path, dict(headers or {}), None, 1

    def post(self, method, params=None, notify=False):
        message = {'jsonrpc': '2.0', 'method': method, **({'params': params} if params is not None else {})}
        if not notify:
            message['id'] = self.next_id
            self.next_id += 1
        headers = {'Content-Type': 'application/json', 'Accept': ACCEPT, **self.headers}
        if self.session:
            headers['Mcp-Session-Id'] = self.session
        self.connection.request('POST', self.path, json.dumps(message), headers)
        response = self.connection.getresponse()
        body = response.read()
        self.session = response.getheader('Mcp-Session-Id') or self.session
        return response, body

    def initialize(self):
        response, body = self.post('initialize', {'protocolVersion': '2025-11-25', 'capabilities': {}, 'clientInfo': {'name': 'test', 'version': '1'}})
        self.post('notifications/initialized', notify=True)
        return response, reply(response, body)

    def close(self):
        self.connection.close()


def reply(response, body):
    """The JSON-RPC response of a POST, framed as JSON or as the last SSE event."""
    return events(body)[-1] if response.getheader('Content-Type') == 'text/event-stream' else json.loads(body)


def events(body):
    return [json.loads(line[len('data: '):]) for line in body.decode().splitlines() if line.startswith('data: ')]


class FixtureServerTest(unittest.TestCase):
    def client(self, server, headers=None, timeout=5):
        client = Client(server, headers, timeout)
        self.addCleanup(client.close)
        return client

    def test_initialize_list_and_call(self):
        with fixture.FixtureServer() as server:
            client = self.client(server)
            response, init = client.initialize()
            self.assertEqual(response.status, 200)
            self.assertEqual(init['result']['serverInfo']['name'], 'fixture-mcp')
            self.assertIsNotNone(client.session)
            _, body = client.post('tools/list', {})
            self.assertEqual([t['name'] for t in json.loads(body)['result']['tools']], ['echo'])
            _, body = client.post('tools/call', {'name': 'echo', 'arguments': {'text': 'hi'}})
            self.assertEqual(json.loads(body)['result']['structuredContent'], {'text': 'hi'})
            self.assertEqual([r['method'] for r in server.requests], ['initialize', 'notifications/initialized', 'tools/list', 'tools/call'])

    def test_requests_without_a_session_are_refused(self):
        with fixture.FixtureServer() as server:
            response, _ = self.client(server).post('tools/list', {})
            self.assertEqual(response.status, 400)

    def test_auth_is_required_when_scripted(self):
        script = {**fixture.DEFAULT_SCRIPT, 'auth': {'header': 'Authorization', 'value': 'Bearer good'}}
        with fixture.FixtureServer(script) as server:
            self.assertEqual(self.client(server).post('initialize', {})[0].status, 401)
            response, _ = self.client(server, {'Authorization': 'Bearer good'}).initialize()
            self.assertEqual(response.status, 200)

    def test_pagination_and_oversize_listing(self):
        script = {**fixture.DEFAULT_SCRIPT, 'pageSize': 2, 'listPadding': 1000, 'tools': [tool(f't{i}') for i in range(5)]}
        with fixture.FixtureServer(script) as server:
            client, names, cursor = self.client(server), [], None
            client.initialize()
            while True:
                _, body = client.post('tools/list', {'cursor': cursor} if cursor else {})
                page = json.loads(body)['result']
                names += [t['name'] for t in page['tools']]
                self.assertTrue(all(len(t['description']) > 1000 for t in page['tools']))
                cursor = page.get('nextCursor')
                if not cursor:
                    break
            self.assertEqual(names, ['t0', 't1', 't2', 't3', 't4'])

    def test_script_changes_apply_to_the_next_scan(self):
        with fixture.FixtureServer() as server:
            client = self.client(server)
            client.initialize()
            server.update(serverInfo={'name': 'fixture-mcp', 'version': '2.0.0'}, tools=[tool('echo', definition={'name': 'echo', 'description': 'changed', 'inputSchema': {'type': 'object'}})])
            _, body = client.post('tools/list', {})
            self.assertEqual(json.loads(body)['result']['tools'][0]['description'], 'changed')
            self.assertEqual(self.client(server).initialize()[1]['result']['serverInfo']['version'], '2.0.0')

    def test_errors_bad_output_and_sse_with_notifications(self):
        script = {**fixture.DEFAULT_SCRIPT, 'tools': [
            tool('fails', error={'code': -32000, 'message': 'upstream broke'}),
            tool('bad', raw='{"jsonrpc": "2.0", "id": '),
            tool('noisy', notifications=['notifications/progress'], result={'content': [{'type': 'text', 'text': 'done'}]})]}
        with fixture.FixtureServer(script) as server:
            client = self.client(server)
            client.initialize()
            _, body = client.post('tools/call', {'name': 'fails', 'arguments': {}})
            self.assertEqual(json.loads(body)['error']['message'], 'upstream broke')
            _, body = client.post('tools/call', {'name': 'bad', 'arguments': {}})
            with self.assertRaises(ValueError):
                json.loads(body)
            response, body = client.post('tools/call', {'name': 'noisy', 'arguments': {}})
            self.assertEqual(response.getheader('Content-Type'), 'text/event-stream')
            self.assertEqual([e.get('method') or e['result']['content'][0]['text'] for e in events(body)], ['notifications/progress', 'done'])

    def test_sse_replies_delays_and_streams_left_open(self):
        script = {**fixture.DEFAULT_SCRIPT, 'reply': 'sse', 'tools': [tool('slow', delay=0.3), tool('open', keepOpen=0.3)]}
        with fixture.FixtureServer(script) as server:
            client = self.client(server)
            client.initialize()
            started = time.monotonic()
            _, body = client.post('tools/call', {'name': 'slow', 'arguments': {}})
            self.assertGreaterEqual(time.monotonic() - started, 0.25)
            self.assertIn('result', events(body)[0])
            started = time.monotonic()
            _, body = client.post('tools/call', {'name': 'open', 'arguments': {}})
            self.assertGreaterEqual(time.monotonic() - started, 0.25)

    def test_list_changed_on_the_get_stream(self):
        with fixture.FixtureServer({**fixture.DEFAULT_SCRIPT, 'listChanged': True}) as server:
            client = self.client(server)
            self.assertTrue(client.initialize()[1]['result']['capabilities']['tools']['listChanged'])
            client.connection.request('GET', client.path, headers={'Accept': 'text/event-stream', 'Mcp-Session-Id': client.session})
            body = client.connection.getresponse().read()
            self.assertEqual(events(body), [{'jsonrpc': '2.0', 'method': 'notifications/tools/list_changed'}])

    def test_trickling_listing_never_finishes(self):
        with fixture.FixtureServer({**fixture.DEFAULT_SCRIPT, 'trickle': {'bytes': 1, 'interval': 0.05}}) as server:
            client = self.client(server)
            client.initialize()
            client.connection.request('POST', client.path, json.dumps({'jsonrpc': '2.0', 'id': 9, 'method': 'tools/list'}),
                                      {'Content-Type': 'application/json', 'Accept': ACCEPT, 'Mcp-Session-Id': client.session})
            response = client.connection.getresponse()
            received, deadline = b'', time.monotonic() + 1
            while time.monotonic() < deadline:
                received += response.read(1)
            self.assertGreater(len(received), 5)
            self.assertFalse(response.isclosed())

    def test_admin_endpoint_replaces_the_script_with_a_token_only(self):
        with fixture.FixtureServer(admin_token='t0ken') as server:
            url = urllib.parse.urlparse(server.url)
            connection = http.client.HTTPConnection(url.hostname, url.port, timeout=5)
            script = {**fixture.DEFAULT_SCRIPT, 'tools': [tool('renamed')]}
            self.addCleanup(connection.close)
            connection.request('PUT', '/_fixture/script', json.dumps(script), {'Authorization': 'Bearer wrong'})
            response = connection.getresponse()
            response.read()
            self.assertEqual(response.status, 401)
            connection.request('PUT', '/_fixture/script', json.dumps(script), {'Authorization': 'Bearer t0ken'})
            response = connection.getresponse()
            response.read()
            self.assertEqual(response.status, 204)
            self.assertEqual(server.script['tools'][0]['definition']['name'], 'renamed')


if __name__ == '__main__':
    unittest.main()
