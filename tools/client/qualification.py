# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Native runner evidence, emitted only after successful service/installer cleanup."""
import hashlib
import json
import os
import platform
from pathlib import Path
import subprocess
from package import ROOT, TARGETS


def record(stage,binary,target=None,installer=None):
    if os.environ.get('GITHUB_ACTIONS')!='true':raise ValueError('Disposable native CI evidence only')
    if stage not in ('service','installer'):raise ValueError('Unknown native qualification stage')
    host=next(line.split(': ',1)[1] for line in subprocess.check_output(['rustc','-vV'],text=True).splitlines() if line.startswith('host: '))
    target=host if target is None else target
    if target not in TARGETS or host!=target:raise ValueError('Native target must match the executing runner')
    source=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    expected=os.environ.get('TOOLGATE_QUALIFICATION_SOURCE_SHA',source)
    if source!=expected:raise ValueError('Native evidence source differs from selected source')
    path=ROOT/'build/client/qualification'/(target+'.json');path.parent.mkdir(parents=True,exist_ok=True)
    identity=dict(formatVersion=1,sourceSha=source,target=target,system=platform.system(),binarySha256=hashlib.sha256(binary.read_bytes()).hexdigest())
    report=json.loads(path.read_text()) if path.exists() else dict(**identity,stages={})
    if {key:report.get(key) for key in identity}!=identity:raise ValueError('Mixed native evidence provenance')
    proof=dict(passed=True,cleanupPassed=True)
    if installer is not None:proof.update(filename=installer.name,sha256=hashlib.sha256(installer.read_bytes()).hexdigest())
    report['stages'][stage]=proof
    temporary=path.with_suffix('.tmp');temporary.write_text(json.dumps(report,indent=2)+'\n');temporary.replace(path)
