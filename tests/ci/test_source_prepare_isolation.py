# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Selected release code cannot execute with host cache access."""
from pathlib import Path
import unittest
import yaml


class SourcePreparationIsolationTests(unittest.TestCase):
    def test_selected_source_runs_only_inside_isolated_container(self):
        root = Path(__file__).resolve().parents[2]
        document = yaml.safe_load((root/'.github/workflows/manual-development-release.yml').read_text())
        step = next(s for s in document['jobs']['prepare']['steps'] if s.get('name') == 'Stamp development packages without publication credentials')
        command = step['run']
        self.assertIn('docker run --rm --network none --cap-drop ALL', command)
        self.assertIn('--security-opt no-new-privileges', command)
        self.assertIn('--user "$(id -u):$(id -g)"', command)
        self.assertIn('$PWD:/controller:ro', command)
        self.assertNotIn('docker.sock', command)
        self.assertNotIn('--env-file', command)
        self.assertNotIn('ACTIONS_RUNTIME_TOKEN', command)
        self.assertNotIn('GH_TOKEN', command)
        self.assertNotIn('secrets', step)
        self.assertNotIn('\npython tools/release/development_source.py', command)
