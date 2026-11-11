# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
import unittest

from tools.quickstart.network_fixture import relay_headers, request_target


class NetworkFixtureTests(unittest.TestCase):
    def test_relay_accepts_control_api_and_keeps_its_query(self):
        target = '/api/control/v1/tool-hosts/kills?after=10&pageSize=50'
        self.assertEqual(request_target(target), target)
        for unsupported in ('/', '/console/', '*', 'https://example.invalid/api/'):
            with self.subTest(unsupported=unsupported), self.assertRaises(ValueError):
                request_target(unsupported)

    def test_relay_rebuilds_framing_and_preserves_end_to_end_headers(self):
        headers = [('Content-Type', 'application/json'), ('Set-Cookie', 'a=1'),
                   ('Set-Cookie', 'b=2'), ('Connection', 'X-Hop'), ('X-Hop', 'drop'),
                   ('Content-Length', '5'), ('Transfer-Encoding', 'chunked')]
        self.assertEqual(relay_headers(headers, exclude=('content-length',)), headers[:3])

    def test_relay_refuses_invalid_header_names_and_values(self):
        for header in [('invalid name', 'value'), ('X-Invalid', 'a\rb'), ('X-Invalid', 'a\nb')]:
            with self.subTest(header=header), self.assertRaises(ValueError):
                relay_headers([header])
