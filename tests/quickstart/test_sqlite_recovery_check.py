# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""The offline gate confirms container state before creating a backup."""

import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    'sqlite_recovery_check', ROOT / 'tools/tenancy/sqlite_recovery_check.py')
recovery = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(recovery)


class RecoveryContainerStateTest(unittest.TestCase):
    def test_stale_listing_of_stopped_container_allows_recovery_setup(self):
        class RecoverySetupReached(Exception):
            pass

        def docker(args):
            if args[0] == 'ps':
                return 'stopped\n'
            if args[0] == 'inspect':
                return 'false false\n'
            if args[:2] == ['volume', 'create']:
                raise RecoverySetupReached
            self.fail(f'Unexpected Docker call: {args}')

        with patch.object(recovery, 'run', side_effect=docker), patch.object(recovery.subprocess, 'run'):
            with self.assertRaises(RecoverySetupReached):
                recovery.qualify('test-image', 'source-volume')

    def test_any_running_or_restarting_container_blocks_recovery(self):
        for active_state in ('true false', 'false true'):
            with self.subTest(active_state=active_state):
                with patch.object(recovery, 'run', side_effect=['stopped\nactive\n', 'false false', active_state]):
                    with self.assertRaisesRegex(ValueError, 'Stop the source composition'):
                        recovery.qualify('test-image', 'source-volume')
