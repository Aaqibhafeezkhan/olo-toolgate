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
        for bad in (event('a.txt', type='examples.files.observed.v1'), event('a.txt', path='outbox/a.txt'),
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


class ProfileTest(unittest.TestCase):
    def test_the_profile_assigns_the_trigger_to_the_example_device_group(self):
        profile = json.loads((HERE / 'hotfolder-trigger.profile.json').read_text(encoding='utf-8'))
        package, = profile['packages']
        self.assertEqual((package['packageId'], package['source']), ('examples.hotfolder', 'PREBUILT_CORE'))
        self.assertEqual(package['deviceGroupIds'], ['ReadAndWriteDeviceGroup'])
        trigger, = package['triggers']
        self.assertEqual(trigger, dict(assignment=ta.ASSIGNMENT, triggerId='examples.hotfolder/file-dropped',
                                       config=dict(folder=ta.INBOX)))


if __name__ == '__main__':
    unittest.main()
