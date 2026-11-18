# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Fresh installation may serialize an absent execution engine as JSON null."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch


class ProfiledDeviceTests(unittest.TestCase):
    def test_fresh_null_engine_is_initialized_and_installed_inventory_is_preserved(self):
        source=Path(__file__).resolve().parents[2]/'debug/profiled/device.py'
        spec=importlib.util.spec_from_file_location('profiled_device',source)
        module=importlib.util.module_from_spec(spec)
        with patch.dict(sys.modules,{'device_entrypoint':SimpleNamespace()}):spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);trust=root/'trust.json';trust.write_text('{}',encoding='utf-8')
            files=root/'files';run=root/'run';run.mkdir();(run/'toolgate-fixture.sock').touch()
            paths={'/installation-trust/trust.json':trust,'/var/lib/olo-toolgate/files':files,'/run':run}
            config=root/'client.json';config.write_text('{"execution":null}',encoding='utf-8')
            with patch.object(module,'Path',side_effect=lambda value:paths[value]),patch.object(module.socket,'gethostname',return_value='fixture'):
                module.configure(config)
                initialized=json.loads(config.read_text(encoding='utf-8'))
                self.assertEqual([],initialized['execution']['tools'])
                self.assertIn('log',initialized['execution']['files']['extensions'])
                installed=dict(initialized);installed['execution']['tools']=[{'toolId':'installed/tool'}]
                config.write_text(json.dumps(installed),encoding='utf-8')
                module.configure(config)
                self.assertEqual(installed,json.loads(config.read_text(encoding='utf-8')))


if __name__=='__main__':unittest.main()
