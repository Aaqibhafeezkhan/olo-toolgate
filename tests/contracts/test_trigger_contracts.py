# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Trigger contract bounds and the worst-case check-in size contract (ADR 058, plan section 8.5).

Every string reachable from trigger messages declares maxLength, every array maxItems, and every
opaque value x-toolgate-max-bytes. A worst-case generator then fills TriggerSync and TriggerSyncAck
for 32 client and 256 Tool Host instances, using the documented priority and paging rules, and
asserts each message stays within its byte share.
"""
import json
import math
import re
import unittest
from pathlib import Path

from jsonschema import Draft202012Validator
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[2]
V2 = ROOT / 'packages/contracts/schemas/v2'
V1 = ROOT / 'packages/contracts/schemas/v1'
KIB = 1024
SYNC_LIMIT = 192 * KIB
ACK_LIMIT = 128 * KIB
CHECK_IN_LIMIT = 256 * KIB
# Array shares of TriggerSync in priority order; 1 KiB stays for the other members (ADR 058).
STATE_SHARE, SNAPSHOT_SHARE, CHECKPOINT_SHARE, FIRED_SHARE = 31 * KIB, 96 * KIB, 16 * KIB, 48 * KIB
WIDE = '\U0001F600'  # one code point, four UTF-8 bytes: the worst case for unpatterned strings
TRIGGER_ROOTS = ('TriggerSync', 'TriggerSyncAck', 'TriggerDesiredDocument', 'TriggerDescriptor', 'TriggerCloudEvent',
                 'TriggerCheckpointUploadRequest', 'TriggerCheckpointUpload', 'TriggerCheckpointMetadata')
# Pattern-constrained strings whose longest valid value cannot be produced by repeating one character.
LONGEST = {
    'TriggerId': 'a' * 31 + '.' + 'a' * 31 + '.' + 'a' * 31 + '/' + 'a' * 32,
    'TriggerEventType': 'a' * 60 + '.' + 'a' * 61 + '.v9999',
    'TriggerTimestamp': '2026-10-31T10:02:11.123456789+05:30',
    'Digest': 'sha256:' + 'f' * 64,
    'traceparent': '00-' + 'f' * 32 + '-' + 'f' * 16 + '-01',
    'TriggerDesiredJws': 'a' * (4 * 1024 * 1024 - 4) + '.a.a',
}


def canonical(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':')).encode('utf-8')


class Schemas:
    def __init__(self):
        docs = [json.loads(p.read_text(encoding='utf-8')) for p in [*V2.glob('*.schema.json'), *V1.glob('*.schema.json')]]
        self.registry = Registry().with_resources((d['$id'], Resource.from_contents(d)) for d in docs)
        self.v2 = {name: (d['$id'], schema) for d in docs if '/v2/' in d['$id'] for name, schema in d['$defs'].items()}
        self.trigger = json.loads((V2 / 'trigger.schema.json').read_text(encoding='utf-8'))['$defs']

    def resolve(self, schema):
        while '$ref' in schema:
            schema = self.v2[schema['$ref'].rsplit('/', 1)[1]][1]
        return schema

    def name(self, schema):
        return schema['$ref'].rsplit('/', 1)[1] if '$ref' in schema else None

    def validator(self, name):
        return Draft202012Validator({'$ref': self.v2[name][0] + '#/$defs/' + name}, registry=self.registry)

    def longest(self, schema, key=None):
        """The largest valid serialized value of a schema, with every optional member present."""
        name = self.name(schema)
        schema = self.resolve(schema)
        if 'const' in schema:
            return schema['const']
        if 'enum' in schema:
            return max(schema['enum'], key=lambda v: len(canonical(v)))
        if 'x-toolgate-max-bytes' in schema:
            limit = schema['x-toolgate-max-bytes']
            return {'d': 'x' * (limit - len(canonical({'d': ''})))}
        kind = schema.get('type')
        if kind == 'object':
            return {k: self.longest(v, k) for k, v in schema['properties'].items()}
        if kind == 'array':
            return [self.longest(schema['items']) for _ in range(schema['maxItems'])]
        if kind == 'boolean':
            return False
        if kind == 'integer':
            return schema['maximum']
        if kind == 'string':
            for label in (name, key):
                if label in LONGEST:
                    return LONGEST[label]
            pattern = schema.get('pattern')
            for fill in ([WIDE] if not pattern else []) + ['a', 'A', 'f', '0', 'Z']:
                value = fill * schema['maxLength']
                if not pattern or re.search(pattern, value):
                    return value
            raise AssertionError(f'No longest value for {name or key}: add it to LONGEST')
        raise AssertionError(f'Unbounded schema {name or key}: {schema}')


def fill(message, key, items, share, limit):
    """Append items to message[key] in order while the array stays within share and the message within limit.

    This is the executor's and Control's packing rule: stop before the limit and leave the rest for the next exchange.
    """
    taken = message.setdefault(key, [])
    size, array = len(canonical(message)), len(canonical(taken))
    for item in items:
        step = len(canonical(item)) + (1 if taken else 0)
        if array + step > share or size + step > limit:
            break
        taken.append(item)
        size, array = size + step, array + step
    if not taken:
        del message[key]
    return taken


class TriggerContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.s = Schemas()

    def test_every_trigger_string_array_and_opaque_value_is_bounded(self):
        problems = []

        def visit(schema, where, seen):
            name = self.s.name(schema)
            if name:
                if name in seen:
                    return
                seen = seen | {name}
            target = self.s.resolve(schema)
            if 'const' in target or 'enum' in target:
                return
            kind = target.get('type')
            if kind == 'string' and 'maxLength' not in target:
                problems.append(where + ': string without maxLength')
            elif kind == 'array':
                if 'maxItems' not in target:
                    problems.append(where + ': array without maxItems')
                visit(target['items'], where + '[]', seen)
            elif kind == 'object' and 'properties' in target:
                for key, child in target['properties'].items():
                    visit(child, where + '.' + key, seen)
            elif kind in (None, 'object') and 'x-toolgate-max-bytes' not in target:
                problems.append(where + ': opaque value without x-toolgate-max-bytes')

        for root in TRIGGER_ROOTS:
            visit({'$ref': 'trigger.schema.json#/$defs/' + root}, root, frozenset())
        for name in self.s.trigger:
            if name.endswith('Frame'):
                visit({'$ref': 'trigger.schema.json#/$defs/' + name}, name, frozenset())
        self.assertEqual([], problems)

    def test_longest_values_are_valid(self):
        for name in self.s.trigger:
            with self.subTest(name=name):
                value = self.s.longest({'$ref': 'trigger.schema.json#/$defs/' + name})
                self.assertEqual([], [e.message for e in self.s.validator(name).iter_errors(value)])

    def test_check_in_carries_trigger_sync_and_ack(self):
        endpoint = json.loads((V1 / 'endpoint.schema.json').read_text(encoding='utf-8'))['$defs']
        self.assertEqual('../v2/trigger.schema.json#/$defs/TriggerSync', endpoint['EndpointCheckIn']['properties']['triggers']['$ref'])
        self.assertEqual('../v2/trigger.schema.json#/$defs/TriggerSyncAck', endpoint['EndpointCheckInAck']['properties']['triggers']['$ref'])
        self.assertLessEqual(SYNC_LIMIT, CHECK_IN_LIMIT)
        self.assertLessEqual(ACK_LIMIT, CHECK_IN_LIMIT)

    def worst_case_sync(self, instances):
        longest = self.s.longest
        report = longest({'$ref': 'trigger.schema.json#/$defs/TriggerInstanceReport'})
        record = longest({'$ref': 'trigger.schema.json#/$defs/TriggerOutboxRecord'})
        checkpoint = longest({'$ref': 'trigger.schema.json#/$defs/TriggerCheckpointReport'})
        page_fields = longest({'$ref': 'trigger.schema.json#/$defs/TriggerSnapshotPage'})
        state = dict(record, event=dict(record['event'], tgkind='state'))
        fired = dict(record, event=dict(record['event'], tgkind='fired'))
        sync = {'observedGeneration': 9007199254740991, 'pending': 1000000}
        states = len(fill(sync, 'events', [state] * instances, STATE_SHARE, SYNC_LIMIT))
        page = dict(page_fields, instances=[])
        per_page = len(fill(page, 'instances', [report] * instances, SNAPSHOT_SHARE, SNAPSHOT_SHARE))
        page['pageCount'] = math.ceil(instances / per_page)
        sync['snapshot'] = page
        fill(sync, 'checkpoints', [checkpoint] * instances, CHECKPOINT_SHARE, SYNC_LIMIT)
        events = sync.pop('events', [])
        sync['events'] = events
        fired_share = len(canonical(events)) + FIRED_SHARE
        fill(sync, 'events', [fired] * 512, fired_share, SYNC_LIMIT)
        self.assertEqual(states, len([e for e in sync['events'] if e['event']['tgkind'] == 'state']))
        return sync, per_page

    def test_worst_case_check_in_fits_for_clients_and_tool_hosts(self):
        for instances in (32, 256):
            with self.subTest(instances=instances):
                sync, per_page = self.worst_case_sync(instances)
                self.assertEqual([], [e.message for e in self.s.validator('TriggerSync').iter_errors(sync)])
                self.assertLessEqual(len(canonical(sync)), SYNC_LIMIT)
                self.assertGreaterEqual(per_page, 1, 'a page must always make progress')
                self.assertLessEqual(math.ceil(instances / per_page), 256, 'pageCount bound')
                self.assertGreaterEqual(len(sync['checkpoints']), 1)
                self.assertGreaterEqual(len([e for e in sync['events'] if e['event']['tgkind'] == 'fired']), 1,
                                        'one maximum-size fired event must fit in the fired share')

    def test_worst_case_ack_fits_with_lease_paging(self):
        longest = self.s.longest
        entry = {key: longest({'$ref': 'trigger.schema.json#/$defs/' + name}) for key, name in (
            ('acked', 'TriggerOutboxAck'), ('dispositions', 'TriggerDisposition'), ('retryLater', 'TriggerRetryLater'),
            ('revoked', 'TriggerRevocation'), ('rejected', 'TriggerRejection'), ('leases', 'TriggerLease'),
            ('snapshotPages', 'TriggerSnapshotPageAck'))}
        for instances in (32, 256):
            with self.subTest(instances=instances):
                head = {'generation': 9007199254740991, 'maxCheckInBytes': CHECK_IN_LIMIT, 'checkInNow': True,
                        'snapshotRequested': False}
                ack = dict(head)
                counts = {'acked': 2 * instances, 'dispositions': 2 * instances, 'retryLater': instances,
                          'revoked': instances, 'rejected': instances, 'leases': instances, 'snapshotPages': 256}
                for key in ('acked', 'dispositions', 'retryLater', 'revoked', 'rejected', 'leases', 'snapshotPages'):
                    maximum = self.s.trigger['TriggerSyncAck']['properties'][key]['maxItems']
                    fill(ack, key, [entry[key]] * min(counts[key], maximum), ACK_LIMIT, ACK_LIMIT)
                self.assertEqual([], [e.message for e in self.s.validator('TriggerSyncAck').iter_errors(ack)])
                self.assertLessEqual(len(canonical(ack)), ACK_LIMIT)
                if instances == 32:
                    unpaged = {k: min(v, self.s.trigger['TriggerSyncAck']['properties'][k]['maxItems']) for k, v in counts.items()}
                    self.assertEqual(unpaged, {k: len(ack.get(k, [])) for k in counts}, 'a client ack never needs paging')
                else:
                    self.assertGreaterEqual(len(ack.get('acked', [])), 1)


if __name__ == '__main__':
    unittest.main()
