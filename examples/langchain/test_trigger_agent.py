# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""The hot-folder graph and event source against a fake HotFolder and a fake Control events API."""
import json
from pathlib import Path
import unittest
import urllib.parse

import trigger_agent as ta

HERE = Path(__file__).resolve().parent


def event(name, id=None, path=None, type=ta.EVENT_TYPE):
    path = path or 'inbox/' + name
    return dict(specversion='1.0', type=type, source='toolgate:tenant:t:trigger-instance:ti-1', id=id or 'file:' + path,
                subject=path, data=dict(name=name, path=path, size=3))


class Folder:
    """The HotFolder tools' contract: read_text reports a missing file as data; move never overwrites."""

    def __init__(self, files, unconfirmed=()):
        self.files, self.unconfirmed, self.calls = dict(files), set(unconfirmed), []

    def call(self, name, arguments):
        self.calls.append((name, dict(arguments)))
        if name in self.unconfirmed:
            raise ta.Unconfirmed(name)
        if name == ta.READ:
            text = self.files.get(arguments['path'])
            return dict(exists=text is not None, text=text or '')
        if name == ta.MOVE:
            if arguments['path'] not in self.files:
                return dict(moved=False)
            if arguments['destination'] in self.files:
                raise ValueError('The destination already exists')
            self.files[arguments['destination']] = self.files.pop(arguments['path'])
            return dict(moved=True)
        raise AssertionError(name)


class GraphTest(unittest.TestCase):
    def graph(self, folder):
        self.shown = []
        return ta.build_graph(folder.call, lambda path, text: self.shown.append((path, text)))

    def test_a_dropped_file_is_displayed_then_moved_to_processed(self):
        folder = Folder({'inbox/order-1042.txt': '2 x widget'})
        result = self.graph(folder).invoke({'event': event('order-1042.txt')})
        self.assertEqual(result['outcome'], 'processed')
        self.assertEqual(self.shown, [('inbox/order-1042.txt', '2 x widget')])
        self.assertEqual(folder.files, {'processed/order-1042.txt': '2 x widget'})
        self.assertEqual([name for name, _ in folder.calls], [ta.READ, ta.MOVE], 'display happens between read and move')

    def test_an_already_processed_file_is_not_displayed_or_moved(self):
        folder = Folder({'processed/order-1042.txt': 'x'})
        result = self.graph(folder).invoke({'event': event('order-1042.txt')})
        self.assertEqual(result['outcome'], 'gone')
        self.assertEqual(self.shown, [])
        self.assertEqual([name for name, _ in folder.calls], [ta.READ])

    def test_an_unconfirmed_operation_stops_and_is_never_retried(self):
        folder = Folder({'inbox/a.txt': 'a'}, unconfirmed={ta.MOVE})
        result = self.graph(folder).invoke({'event': event('a.txt')})
        self.assertEqual(result['outcome'], 'move-unconfirmed')
        self.assertEqual([name for name, _ in folder.calls], [ta.READ, ta.MOVE])
        folder = Folder({'inbox/a.txt': 'a'}, unconfirmed={ta.READ})
        self.assertEqual(self.graph(folder).invoke({'event': event('a.txt')})['outcome'], 'read-unconfirmed')
        self.assertEqual(self.shown, [], 'nothing unconfirmed is displayed')

    def test_events_outside_the_inbox_contract_are_ignored_without_a_tool_call(self):
        folder = Folder({'inbox/a.txt': 'a'})
        graph = self.graph(folder)
        for bad in (event('a.txt', type='examples.hotfolder.file-dropped.v1'), event('a.txt', path='outbox/a.txt'),
                    event('../a.txt', path='inbox/../a.txt'), event('sub/a.txt', path='inbox/sub/a.txt'),
                    dict(type=ta.EVENT_TYPE, data='inbox/a.txt')):
            self.assertEqual(graph.invoke({'event': bad})['outcome'], 'ignored', bad)
        self.assertEqual(folder.calls, [])


class Control:
    """GET triggers/instances and triggers/events with Control's paging: a token only when more pages follow."""

    def __init__(self, page_size=2):
        self.items, self.page_size = [], page_size

    def get(self, path):
        route, _, query = path.partition('?')
        query = dict(urllib.parse.parse_qsl(query))
        if route.endswith('/triggers/instances'):
            return dict(items=[dict(instanceId='ti-1', assignment=query['assignment'])])
        start = int(query.get('pageToken', '0'))
        rows = self.items[start:start + self.page_size + 1]
        page = dict(items=rows[:self.page_size])
        if len(rows) > self.page_size:
            page['nextPageToken'] = str(start + self.page_size)
        return page


class EventsTest(unittest.TestCase):
    def test_each_event_is_handed_out_once_across_pages_and_polls(self):
        control = Control()
        source = ta.ControlEvents(control.get)
        self.assertEqual(list(source.poll()), [])
        control.items += [dict(event=event('a.txt')), dict(event=event('b.txt'))]
        self.assertEqual([e['subject'] for e in source.poll()], ['inbox/a.txt', 'inbox/b.txt'])
        self.assertEqual(list(source.poll()), [], 'a re-read of the last page hands nothing out again')
        control.items += [dict(event=event('c.txt')), dict(event=event('d.txt'), disposition=dict(disposition='LOSS', reason='x')),
                          dict(event=event('e.txt'))]
        self.assertEqual([e['subject'] for e in source.poll()], ['inbox/c.txt', 'inbox/e.txt'], 'dispositions are not events')
        self.assertEqual(list(source.poll()), [])

    def test_run_drives_the_graph_once_per_event(self):
        control = Control()
        control.items += [dict(event=event('a.txt')), dict(event=event('a.txt'))]
        folder = Folder({'inbox/a.txt': 'hello'})
        graph = ta.build_graph(folder.call, lambda path, text: None)
        lines = []
        outcomes = ta.run(ta.ControlEvents(control.get), graph, rounds=2, interval=0, report=lambda line, **_: lines.append(line))
        self.assertEqual(outcomes, ['processed'])
        self.assertEqual(lines, ['inbox/a.txt: processed'])


class FakeSocket:
    """A toolgate.triggers.v1 server stand-in: queued server messages, recorded client messages, then a close."""

    def __init__(self, server, script):
        self.server, self.inbox, self.sent, self.closed = server, list(script), [], False

    def send(self, text):
        self.sent.append(json.loads(text))

    def recv(self, timeout):
        if self.closed:
            raise ConnectionError('closed')
        if not self.inbox:
            raise TimeoutError()
        item = self.inbox.pop(0)
        if item == 'close':
            self.closed = True
            raise ConnectionError('closed by server')
        return json.dumps(item)

    def close(self):
        self.closed = True


class SocketServer:
    def __init__(self, scripts):
        self.scripts, self.sockets, self.tickets, self.offered = list(scripts), [], 0, []

    def ticket(self):
        self.tickets += 1
        return 'T' * 42 + str(self.tickets % 10)

    def connect(self, subprotocols):
        self.offered.append(subprotocols)
        socket = FakeSocket(self, self.scripts.pop(0))
        self.sockets.append(socket)
        return socket


def message(name, cursor):
    return dict(type='event', subscription=ta.SUBSCRIPTION, cursor=cursor, event=event(name))


class SocketEventsTest(unittest.TestCase):
    def test_events_are_acknowledged_after_handling_deduplicated_and_resumed_with_a_new_ticket(self):
        server = SocketServer([
            [dict(type='subscribed', subscription=ta.SUBSCRIPTION), message('a.txt', '0000000000000001'), 'close'],
            [dict(type='subscribed', subscription=ta.SUBSCRIPTION), message('a.txt', '0000000000000001'), message('b.txt', '0000000000000002')],
        ])
        lines = []
        source = ta.SocketEvents(server.connect, server.ticket, wait=0.05, report=lambda line, **_: lines.append(line))
        handled = []
        for item in source.poll():
            # The ack for this cursor is not sent before the graph has handled the event.
            self.assertFalse(any(m.get('type') == 'ack' for m in server.sockets[0].sent))
            handled.append(item['subject'])
        self.assertEqual(handled, ['inbox/a.txt'])
        first = server.sockets[0].sent
        self.assertEqual(first[0], dict(type='subscribe', subscription=ta.SUBSCRIPTION))
        self.assertEqual(first[1], dict(type='ack', subscription=ta.SUBSCRIPTION, cursor='0000000000000001'))
        # The server closed: the next poll reconnects with a fresh single-use ticket and resumes.
        handled += [item['subject'] for item in source.poll()]
        self.assertEqual(handled, ['inbox/a.txt', 'inbox/b.txt'], 'a re-delivered event is handled once')
        self.assertEqual(server.tickets, 2)
        self.assertEqual([offer[0] for offer in server.offered], [ta.PROTOCOL, ta.PROTOCOL])
        self.assertTrue(all(offer[1].startswith('ticket.') for offer in server.offered))
        acks = [m['cursor'] for m in server.sockets[1].sent if m['type'] == 'ack']
        self.assertEqual(acks, ['0000000000000001', '0000000000000002'], 'every delivery is acknowledged, duplicates too')

    def test_gaps_are_reported_and_revocation_stops_the_agent(self):
        server = SocketServer([[dict(type='gap', subscription=ta.SUBSCRIPTION, fromCursor='0000000000000002', toCursor='0000000000000009',
                                     reason='RetentionExpired'), dict(type='revoked', subscription=ta.SUBSCRIPTION, reason='GrantRevoked')]])
        lines = []
        source = ta.SocketEvents(server.connect, server.ticket, wait=0.05, report=lambda line, **_: lines.append(line))
        with self.assertRaisesRegex(ValueError, 'GrantRevoked'):
            list(source.poll())
        self.assertIn('gap 0000000000000002..0000000000000009: RetentionExpired', lines)

    def test_the_subscription_is_created_once(self):
        calls = []

        def api(path, body=None):
            calls.append((path, body))
            if body is None and len(calls) == 1:
                raise ValueError('HTTP 404')
            return {'metadata': {'name': ta.SUBSCRIPTION}}

        ta.ensure_subscription(api, 1000)
        created = calls[1][1]
        self.assertEqual(created['spec']['sources'], {'assignments': [ta.ASSIGNMENT]})
        self.assertEqual(created['spec']['expiresAtUnixMs'], 1000 + 30 * 86400000)
        calls.clear()
        ta.ensure_subscription(lambda path, body=None: calls.append((path, body)) or {'metadata': {}}, 1000)
        self.assertEqual(len(calls), 1, 'an existing subscription is kept')


class ProfileTest(unittest.TestCase):
    def test_the_profile_assigns_the_trigger_to_the_example_device_group(self):
        profile = json.loads((HERE / 'hotfolder-trigger.profile.json').read_text(encoding='utf-8'))
        package, = profile['packages']
        self.assertEqual((package['packageId'], package['source']), ('examples.inbox-watch', 'PREBUILT_CORE'))
        self.assertEqual(package['deviceGroupIds'], ['ReadAndWriteDeviceGroup'])
        trigger, = package['triggers']
        self.assertEqual(trigger, dict(assignment=ta.ASSIGNMENT, triggerId='examples.inbox-watch/new-file',
                                       config=dict(folder=ta.INBOX, extension='.txt')))


if __name__ == '__main__':
    unittest.main()
