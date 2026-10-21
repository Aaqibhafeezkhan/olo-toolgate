# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Reject missing, stale, cross-compiled or mismatched native release qualification."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools/client'))
from package import TARGETS


def verify(directory,assets,source):
    if not re.fullmatch('[a-f0-9]{40}',source):raise ValueError('Exact source commit required')
    reports={path.stem:json.loads(path.read_text()) for path in directory.glob('*.json')}
    if set(reports)!=TARGETS:raise ValueError('All six native targets must pass; no substituted cross-compilation evidence')
    installers=json.loads((assets/'installers.json').read_text())['artifacts']
    by_target={row['target']:row for row in installers}
    if set(by_target)!=TARGETS or len(installers)!=len(TARGETS):raise ValueError('Matching six-target installer manifest required')
    for target,report in reports.items():
        expected_system='Windows' if 'windows' in target else 'Darwin' if 'apple' in target else 'Linux'
        if report.get('formatVersion')!=1 or report.get('sourceSha')!=source or report.get('target')!=target or report.get('system')!=expected_system or not re.fullmatch('[a-f0-9]{64}',report.get('binarySha256','')):raise ValueError('Native source/target provenance mismatch')
        if set(report.get('stages',{}))!={'service','installer'}:raise ValueError('Service and installer qualification both required')
        for stage in report['stages'].values():
            if stage.get('passed') is not True or stage.get('cleanupPassed') is not True:raise ValueError('Native qualification or cleanup failed')
        evidence=report['stages']['installer'];artifact=by_target[target]
        if evidence.get('filename')!=artifact['filename'] or evidence.get('sha256')!=artifact['sha256']:raise ValueError('Installer differs from qualified bytes')
        if Path(artifact['filename']).name!=artifact['filename']:raise ValueError('Literal installer filename required')
        file=assets/artifact['filename']
        if file.is_symlink() or not file.is_file() or file.stat().st_size!=artifact['bytes'] or hashlib.sha256(file.read_bytes()).hexdigest()!=artifact['sha256']:raise ValueError('Installer artifact integrity mismatch')
    return dict(formatVersion=1,sourceSha=source,nativeTargets=sorted(reports),allNativeServiceAndInstallerGatesPassed=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--directory',type=Path,required=True);parser.add_argument('--assets',type=Path,required=True);parser.add_argument('--source',default=None);parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
    source=args.source or subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    report=verify(args.directory,args.assets,source);args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(report,indent=2)+'\n')
