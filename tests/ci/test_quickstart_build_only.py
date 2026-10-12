# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Focused debug startup builds the real images without running unrelated suites."""
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from tools.quickstart import check


class QuickstartBuildOnlyTests(unittest.TestCase):
    def test_prebuilt_ui_reuses_shared_artifact_but_default_builds_from_source(self):
        for prebuilt in (False, True):
            with self.subTest(prebuilt=prebuilt), patch.object(check, 'run', return_value=SimpleNamespace(stdout='a'*40)) as run:
                check.build_images('fixture', prebuilt_ui=prebuilt)
            control = next(call.args[0] for call in run.call_args_list if 'apps/control-plane/Dockerfile' in call.args[0])
            self.assertEqual('ADMIN_UI_SOURCE=console-prebuilt' in control, prebuilt)

    def test_build_only_builds_the_requested_image_without_smoke_suites(self):
        with patch.object(sys, 'argv', ['check.py', '--build', '--build-only', '--image', 'fixture']), \
                patch.object(check, 'build_images') as build, patch.object(check, 'smoke') as smoke:
            check.main()
        build.assert_called_once_with('fixture', None, False)
        smoke.assert_not_called()

    def test_build_only_requires_a_build(self):
        with patch.object(sys, 'argv', ['check.py', '--build-only']), patch.object(check, 'build_images') as build:
            with self.assertRaises(SystemExit) as rejected:
                check.main()
        self.assertEqual(rejected.exception.code, 2)
        build.assert_not_called()


if __name__ == '__main__':
    unittest.main()
