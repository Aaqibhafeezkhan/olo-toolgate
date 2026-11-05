# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Every independent production Noble root must bootstrap fixed APT."""
from pathlib import Path
import re
import unittest
import yaml

ROOT=Path(__file__).resolve().parents[2]


class UbuntuImageSecurityTests(unittest.TestCase):
    def test_all_independent_noble_stages_patch_apt_before_other_installs(self):
        found=[]
        for path in [*ROOT.glob('apps/**/*Dockerfile*'),*ROOT.glob('examples/**/*Dockerfile*')]:
            if not path.is_file():continue
            stages=re.split(r'^FROM ',path.read_text(),flags=re.MULTILINE)[1:]
            for stage in stages:
                base=stage.splitlines()[0].split()[0]
                if not (base.startswith('ubuntu:24.04') or 'temurin:21-jdk-noble' in base):continue
                with self.subTest(path=str(path.relative_to(ROOT)),base=base):
                    found.append(path)
                    self.assertIn('--only-upgrade apt libapt-pkg6.0t64',stage)
                    self.assertIn('Acquire::Retries=0 update',stage)
                    self.assertIn('Acquire::Retries=0 install',stage)
                    for package in ('apt','libapt-pkg6.0t64'):
                        self.assertRegex(stage,r"dpkg --compare-versions .+ "+re.escape(package)+r'.+ ge 2\.8\.4ubuntu0\.1')
                    self.assertLess(stage.index('--only-upgrade apt'),stage.index('ge 2.8.4ubuntu0.1'))
        self.assertEqual(4,len(found),'inventory independent production Noble roots')

    def test_secured_base_is_rejected_before_network_install_if_unpatched(self):
        stage=(ROOT/'apps/device/secured.Dockerfile').read_text()
        self.assertLess(stage.index('ge 2.8.4ubuntu0.1'),stage.index('apt-get'))
        self.assertIn('libapt-pkg6.0t64',stage)

    def test_device_and_secured_image_scans_remain_blocking(self):
        jobs=yaml.safe_load((ROOT/'.github/workflows/images.yml').read_text())['jobs']
        for name in ('device','secured-device'):
            with self.subTest(job=name):
                scan=next(step for step in jobs[name]['steps']
                          if '--severity HIGH,CRITICAL' in step.get('run',''))
                self.assertIn('--exit-code 1',scan['run'])
                self.assertNotIn('continue-on-error',scan)
