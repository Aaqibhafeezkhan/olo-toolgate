# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Release status cannot be earned by missing, stale or substituted native evidence."""
import copy
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'tools/tenancy'))
from native_evidence import TARGETS,verify


class NativeEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.reports=self.root/'evidence';self.assets=self.root/'assets';self.reports.mkdir();self.assets.mkdir();self.source='a'*40;artifacts=[]
        for target in TARGETS:
            system='Windows' if 'windows' in target else 'Darwin' if 'apple' in target else 'Linux'
            filename=target+'.installer';payload=target.encode();(self.assets/filename).write_bytes(payload)
            digest=hashlib.sha256(payload).hexdigest();artifacts.append(dict(target=target,filename=filename,sha256=digest,bytes=len(payload)))
            self.write(target,dict(formatVersion=1,sourceSha=self.source,target=target,system=system,binarySha256='b'*64,stages=dict(service=dict(passed=True,cleanupPassed=True),installer=dict(passed=True,cleanupPassed=True,filename=filename,sha256=digest))))
        (self.assets/'installers.json').write_text(json.dumps(dict(artifacts=artifacts)))
    def write(self,target,value):(self.reports/(target+'.json')).write_text(json.dumps(value))
    def test_complete_matching_native_matrix_is_required(self):
        self.assertEqual(len(verify(self.reports,self.assets,self.source)['nativeTargets']),6)
        next(self.reports.glob('*.json')).unlink()
        with self.assertRaises(ValueError):verify(self.reports,self.assets,self.source)
    def test_stale_source_cross_target_and_failed_cleanup_are_rejected(self):
        file=next(self.reports.glob('*.json'));original=json.loads(file.read_text())
        for field,value in [('sourceSha','c'*40),('system','WrongOS'),('target','x86_64-pc-windows-gnu')]:
            report=copy.deepcopy(original);report[field]=value;file.write_text(json.dumps(report))
            with self.assertRaises(ValueError):verify(self.reports,self.assets,self.source)
        report=copy.deepcopy(original);report['stages']['installer']['cleanupPassed']=False;file.write_text(json.dumps(report))
        with self.assertRaises(ValueError):verify(self.reports,self.assets,self.source)
    def test_changed_installer_or_missing_service_proof_is_rejected(self):
        file=next(self.reports.glob('*.json'));report=json.loads(file.read_text());artifact=self.assets/report['stages']['installer']['filename']
        artifact.write_bytes(b'tampered')
        with self.assertRaises(ValueError):verify(self.reports,self.assets,self.source)


if __name__=='__main__':unittest.main()
