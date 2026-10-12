# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Native installer smoke failures retain the installer's diagnostic log."""
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'tools/client'))
import installer_smoke


class InstallerSmokeTest(unittest.TestCase):
    def test_windows_fresh_install_selects_remote_fixture_and_repairs_keep_gateway(self):
        with patch.object(installer_smoke, 'run') as run:
            installer_smoke.windows_install(Path('client.setup.exe'), 'https://control.example.invalid')
            self.assertIn('/SERVER=https://control.example.invalid', run.call_args.args[0])
            installer_smoke.windows_install(Path('client.setup.exe'))
            self.assertFalse(any(arg.startswith('/SERVER=') for arg in run.call_args.args[0]))

    def test_windows_failure_prints_setup_log_and_preserves_exit_status(self):
        failure=subprocess.CalledProcessError(7, ['client.setup.exe'])
        def failed_setup(arguments):
            log=Path(next(arg.removeprefix('/LOG=') for arg in arguments if arg.startswith('/LOG=')))
            log.write_text('Could not verify the gateway connection.', encoding='utf-8-sig')
            raise failure
        with patch.object(installer_smoke, 'run', side_effect=failed_setup), patch('builtins.print') as output:
            with self.assertRaises(subprocess.CalledProcessError) as caught:
                installer_smoke.windows_install(Path('client.setup.exe'))
            self.assertIs(caught.exception, failure)
            output.assert_called_once_with('Could not verify the gateway connection.', flush=True)

    def test_windows_failure_without_log_preserves_original_error(self):
        failure=subprocess.CalledProcessError(7, ['client.setup.exe'])
        with patch.object(installer_smoke, 'run', side_effect=failure), patch('builtins.print') as output:
            with self.assertRaises(subprocess.CalledProcessError) as caught:
                installer_smoke.windows_install(Path('client.setup.exe'))
            self.assertIs(caught.exception, failure)
            output.assert_not_called()
