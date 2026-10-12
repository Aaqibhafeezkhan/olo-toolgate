# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Historical evidence stays visible without weakening active or license gates."""
from contextlib import contextmanager
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch

from tools import source_scan

HISTORICAL='tool-sdk/spec/sdk-compat/2026-10-10/pins/java/pom.xml'
VULNERABILITY=dict(VulnerabilityID='CVE-fixture',Severity='HIGH',PkgName='jackson')


def report(*results):
    return dict(SchemaVersion=2,Results=list(results))


class SourceScanTests(unittest.TestCase):
    def test_same_vulnerability_in_active_dependencies_still_blocks(self):
        raw=report(dict(Target=HISTORICAL,Vulnerabilities=[VULNERABILITY]),
                   dict(Target='tool-sdk/java-sdk/protocol/gradle.lockfile',Vulnerabilities=[VULNERABILITY]),
                   dict(Target=HISTORICAL+'/new/pom.xml',Vulnerabilities=[VULNERABILITY]))
        blocking,historical=source_scan.split_findings(raw,{HISTORICAL})
        self.assertEqual([r['Target'] for r in blocking],
                         ['tool-sdk/java-sdk/protocol/gradle.lockfile',HISTORICAL+'/new/pom.xml'])
        self.assertEqual(historical[0]['Vulnerabilities'],[VULNERABILITY])
        self.assertEqual(len(raw['Results'][0]['Vulnerabilities']),1)

    def test_historical_license_findings_remain_blocking(self):
        license=dict(Name='forbidden-fixture',Severity='CRITICAL')
        raw=report(dict(Target=HISTORICAL,Vulnerabilities=[VULNERABILITY],Licenses=[license]))
        blocking,historical=source_scan.split_findings(raw,{HISTORICAL})
        self.assertEqual(blocking[0]['Licenses'],[license])
        self.assertEqual(blocking[0]['Vulnerabilities'],[])
        self.assertEqual(historical[0]['Vulnerabilities'],[VULNERABILITY])

    def test_changed_or_new_evidence_is_not_historical(self):
        with tempfile.TemporaryDirectory() as temporary:
            archive=Path(temporary)/'source.tar'
            for content in (b'approved evidence',b'changed evidence'):
                with tarfile.open(archive,'w') as source:
                    entry=tarfile.TarInfo(HISTORICAL);entry.size=len(content)
                    source.addfile(entry,io.BytesIO(content))
                    entry=tarfile.TarInfo('tool-sdk/spec/sdk-compat/2026-10-12/pins/java/pom.xml')
                    entry.size=len(content);source.addfile(entry,io.BytesIO(content))
                with patch.object(source_scan,'FROZEN_MANIFESTS',
                                  {HISTORICAL:hashlib.sha256(b'approved evidence').hexdigest()}):
                    targets=source_scan.historical_targets(archive)
                self.assertEqual(targets,{HISTORICAL} if content==b'approved evidence' else set())
                blocking,_=source_scan.split_findings(report(dict(Target=HISTORICAL,Vulnerabilities=[VULNERABILITY])),targets)
                self.assertEqual(bool(blocking),content!=b'approved evidence')

    def test_approved_hashes_match_the_immutable_git_record(self):
        for name,expected in source_scan.FROZEN_MANIFESTS.items():
            raw=subprocess.check_output(['git','show','HEAD:'+name],cwd=source_scan.ROOT)
            with self.subTest(manifest=name):self.assertEqual(hashlib.sha256(raw).hexdigest(),expected)

    def test_unknown_or_malformed_reports_fail_closed(self):
        for raw in ({},dict(SchemaVersion=3,Results=[]),report(dict(Target='active',Vulnerabilities={})),
                    report(dict(Target='active',Vulnerabilities=[{}])),
                    report(dict(Target='active',Vulnerabilities=[dict(Severity='unexpected')]))):
            with self.subTest(report=raw),self.assertRaises(ValueError):source_scan.split_findings(raw,set())

    def test_scan_saves_complete_and_separate_reports_before_blocking(self):
        for active in (False,True):
            with self.subTest(active=active),tempfile.TemporaryDirectory() as temporary:
                root=Path(temporary);archive=root/'source.tar'
                @contextmanager
                def snapshot():yield archive
                raw=report(dict(Target=HISTORICAL,Vulnerabilities=[VULNERABILITY]))
                if active:raw['Results'].append(dict(Target='Cargo.lock',Vulnerabilities=[VULNERABILITY]))
                result=subprocess.CompletedProcess([],0,stdout=json.dumps(raw),stderr='')
                with patch.object(source_scan,'ROOT',root),patch.object(source_scan,'snapshot',snapshot), \
                        patch.object(source_scan,'historical_targets',return_value={HISTORICAL}), \
                        patch.object(source_scan.subprocess,'run',return_value=result) as run:
                    if active:
                        with self.assertRaises(RuntimeError):source_scan.scan()
                    else:source_scan.scan()
                output=root/'build/source-scan'
                self.assertEqual(json.loads((output/'full.json').read_text()),raw)
                self.assertEqual(len(json.loads((output/'historical.json').read_text())['Results']),1)
                self.assertEqual(bool(json.loads((output/'blocking.json').read_text())['Results']),active)
                self.assertNotIn('--skip',run.call_args.args[0][-1])

    def test_scanner_failure_cannot_become_a_clean_report(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary)
            @contextmanager
            def snapshot():yield root/'source.tar'
            result=subprocess.CompletedProcess([],1,stdout='{}',stderr='scanner unavailable\n')
            with patch.object(source_scan,'ROOT',root),patch.object(source_scan,'snapshot',snapshot), \
                    patch.object(source_scan.subprocess,'run',return_value=result), \
                    self.assertRaises(subprocess.CalledProcessError):source_scan.scan()
            self.assertFalse((root/'build/source-scan/full.json').exists())
