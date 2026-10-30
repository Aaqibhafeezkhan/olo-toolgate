# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Manual development builds refuse a Quickstart image that never becomes ready."""
import subprocess
import unittest
from unittest.mock import MagicMock, patch
from tools.release import manual_development


class StartupGateTests(unittest.TestCase):
    def gate(self,running,ready):
        def run(*args,**kwargs):
            stdout={'run':'container-id\n','port':'127.0.0.1:49153\n','inspect':'true\n' if running else 'false\n'}.get(args[1],'')
            return subprocess.CompletedProcess(args,0,stdout=stdout)
        response=MagicMock(status=200)
        response.__enter__.return_value=response
        removed=MagicMock()
        with patch.object(manual_development,'run',run), patch.object(manual_development.subprocess,'run',removed), \
                patch.object(manual_development.urllib.request,'urlopen',return_value=response if ready else None,
                             side_effect=None if ready else OSError('refused')), \
                patch.object(manual_development.time,'sleep'):
            manual_development.startup_gate('olo-toolgate-quickstart:manual-development',timeout=5)
        removed.assert_called_once()
        self.assertEqual(removed.call_args.args[0],['docker','rm','-f','container-id'])

    def test_ready_image_passes(self):
        self.gate(running=True,ready=True)

    def test_image_that_exits_before_ready_is_refused(self):
        with self.assertRaisesRegex(RuntimeError,'refusing to publish'):
            self.gate(running=False,ready=False)


if __name__=='__main__':
    unittest.main()
