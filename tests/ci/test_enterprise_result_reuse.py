# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Reusing successful reports must not run tests again or accept missing/failed evidence."""
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from tools.enterprise import check


class EnterpriseResultReuseTests(unittest.TestCase):
    def test_all_required_suites_must_be_present_and_successful(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            folders = ('packages/contracts/java/build/test-results/test',
                       'backend-libs/package-archive/build/test-results/test',
                       'apps/control-plane/build/test-results/test',
                       'apps/control-plane/build/test-results/enterpriseTest')
            for folder in folders:
                path = root/folder
                path.mkdir(parents=True)
                (path/'TEST-fixture.xml').write_text('<testsuite name="Fixture" tests="1" failures="0" errors="0"><testcase name="works"/></testsuite>')
            with patch.object(check, 'ROOT', root), patch.object(check, 'run') as run:
                check.evidence()
                self.assertTrue((root/'build/enterprise/conformance.json').is_file())
                for folder in folders:
                    report = root/folder/'TEST-fixture.xml'
                    content = report.read_text()
                    report.unlink()
                    with self.assertRaisesRegex(ValueError, 'Missing'): check.evidence()
                    report.write_text(content.replace('failures="0"','failures="1"'))
                    with self.assertRaisesRegex(ValueError, 'Successful'): check.evidence()
                    report.write_text(content)
            run.assert_not_called()


if __name__ == '__main__':
    unittest.main()
