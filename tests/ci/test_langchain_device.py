# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""The example uses the current generic executor; startup must not require removed built-ins."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[2]


class LangChainDeviceTests(unittest.TestCase):
    def test_startup_preserves_identity_and_existing_files_without_any_profile_cli(self):
        spec = importlib.util.spec_from_file_location('langchain_device', ROOT/'examples/langchain/device.py')
        module = importlib.util.module_from_spec(spec)
        cli = Mock(side_effect=AssertionError('No built-in profile export exists'))
        with patch.dict(sys.modules, {'device_entrypoint': SimpleNamespace(cli=cli)}):
            spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as folder:
            state = Path(folder)/'state';state.mkdir()
            files = state/'hotfolder';files.mkdir()
            (files/'support-tickets.json').write_text('existing input')
            fixtures = Path(folder)/'fixtures';fixtures.mkdir()
            (fixtures/'support-tickets.json').write_text('fictional seed')
            config = Path(folder)/'client.json'
            config.write_text(json.dumps(dict(stateDirectory=str(state),serverUrl='https://localhost:18451',
                tenantId='tenant-fixture',deployment={'releaseKeys':['public']},tools={'retired':'configuration'})))
            module.FIXTURES = fixtures
            module.configure(config)
            value = json.loads(config.read_text())
            self.assertEqual(value['tenantId'],'tenant-fixture')
            self.assertEqual(value['deployment'],{'releaseKeys':['public']})
            self.assertNotIn('tools',value)
            self.assertEqual(value['execution']['tools'],[])
            self.assertEqual(value['execution']['runtimes'],[])
            self.assertEqual(value['execution']['files']['root'],str(files))
            self.assertEqual((files/'support-tickets.json').read_text(),'existing input')
        cli.assert_not_called()
