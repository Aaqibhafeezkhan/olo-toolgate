# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""The MCP proxy definition digest vectors (tool-sdk/spec/mcp-proxy) agree with the specification.

This is the reference implementation the vectors were generated from; Control (Java) and the Tool Host (Rust) test
their own implementations against the same file.
"""
import hashlib
import json
import math
import unittest
from decimal import Decimal
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
VECTORS = ROOT/'tool-sdk/spec/mcp-proxy/definition-digest.json'
MEMBERS = ('name', 'title', 'description', 'inputSchema', 'outputSchema', 'annotations')
ESCAPES = {'"': '\\"', '\\': '\\\\', '\b': '\\b', '\f': '\\f', '\n': '\\n', '\r': '\\r', '\t': '\\t'}


def number(value):
    """ECMAScript Number::toString; repr gives the shortest round-tripping digits."""
    if isinstance(value, int):
        return str(value)
    if not math.isfinite(value):
        raise ValueError('not a finite number')
    if value == 0:
        return '0'
    shortest = Decimal(repr(abs(value))).normalize().as_tuple()
    digits = ''.join(map(str, shortest.digits))
    k, n = len(digits), len(digits) + shortest.exponent
    sign = '-' if value < 0 else ''
    if k <= n <= 21:
        return sign + digits + '0' * (n - k)
    if 0 < n <= 21:
        return sign + digits[:n] + '.' + digits[n:]
    if -6 < n <= 0:
        return sign + '0.' + '0' * -n + digits
    e = n - 1
    return sign + digits[0] + ('.' + digits[1:] if k > 1 else '') + 'e' + ('+' if e >= 0 else '-') + str(abs(e))


def string(value):
    return '"' + ''.join(ESCAPES.get(c) or (f'\\u{ord(c):04x}' if ord(c) < 0x20 else c) for c in value) + '"'


def jcs(value):
    if value is None:
        return 'null'
    if isinstance(value, bool):
        return 'true' if value else 'false'
    if isinstance(value, (int, float)):
        return number(value)
    if isinstance(value, str):
        return string(value)
    if isinstance(value, list):
        return '[' + ','.join(jcs(v) for v in value) + ']'
    keys = sorted(value, key=lambda k: k.encode('utf-16-be'))
    return '{' + ','.join(string(k) + ':' + jcs(value[k]) for k in keys) + '}'


def canonical(tool):
    return jcs({k: tool[k] for k in MEMBERS if k in tool})


def digest(tool):
    return 'sha256:' + hashlib.sha256(canonical(tool).encode('utf-8')).hexdigest()


class DefinitionDigestTest(unittest.TestCase):
    def test_number_formatting_follows_ecmascript(self):
        for value, text in ((1.0, '1'), (0.5, '0.5'), (-0.0, '0'), (1e21, '1e+21'), (1e-7, '1e-7'), (123e-9, '1.23e-7'),
                            (0.000001, '0.000001'), (1.5e300, '1.5e+300'), (2**53, '9007199254740992'), (12.25, '12.25')):
            self.assertEqual(number(value), text, value)

    def test_vectors_match_the_reference_implementation(self):
        cases = json.loads(VECTORS.read_text(encoding='utf-8'))['cases']
        self.assertGreaterEqual(len(cases), 8)
        self.assertEqual(len({c['name'] for c in cases}), len(cases))
        for case in cases:
            with self.subTest(case=case['name']):
                self.assertEqual(canonical(case['tool']), case['canonical'])
                self.assertEqual(digest(case['tool']), case['digest'])

    def test_any_change_of_a_kept_member_changes_the_digest(self):
        cases = {c['name']: c for c in json.loads(VECTORS.read_text(encoding='utf-8'))['cases']}
        self.assertEqual(cases['minimal']['digest'], cases['dropped-members']['digest'])
        self.assertNotEqual(cases['nfc']['digest'], cases['nfd']['digest'])
        self.assertNotEqual(cases['minimal']['digest'], cases['description-changed']['digest'])


if __name__ == '__main__':
    unittest.main()
