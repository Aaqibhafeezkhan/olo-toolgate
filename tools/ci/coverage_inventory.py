# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Inventory tracked source coverage; absent reports are never interpreted as 100%."""
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

EXTENSIONS = {'.java', '.rs', '.py', '.ts', '.tsx', '.js', '.mjs', '.cs', '.php', '.sh', '.ps1'}
EXCLUSIONS = {'test', 'tests', 'fixtures', 'raw', 'pins', 'generated', 'dist', 'build', 'node_modules'}


def exclusion(path):
    if set(path.parts) & EXCLUSIONS or path.name.startswith('test_') or '.test.' in path.name:
        return 'Tests, fixtures, generated output or frozen compatibility evidence'
    if path.name.endswith('.d.ts'):
        return 'Type declarations have no executable code'
    return None


def collect(root):
    entries = []
    for name in subprocess.check_output(['git', 'ls-files'], cwd=root, text=True).splitlines():
        path = Path(name)
        if path.suffix not in EXTENSIONS:
            continue
        reason = exclusion(path)
        entries.append({'path': name, 'language': path.suffix, 'exclusion': reason})
    return entries


def append_summary(root):
    inventory = collect(root)
    output = root / 'build/coverage'
    output.mkdir(parents=True, exist_ok=True)
    (output / 'source-inventory.json').write_text(json.dumps(inventory, indent=2) + '\n')
    lines = ['\n## Coverage completeness', '', 'Missing reports are incomplete measurement, not zero measured coverage. Untested files in configured source sets remain in their collector denominator.', '', '| Scope | Report |', '| --- | --- |']
    reports = {
        'Java: all root Gradle modules': 'build/reports/jacoco/jacocoRootReport/jacocoRootReport.xml',
        'JavaScript: browser extension, Node tools and contracts': 'build/coverage/javascript/lcov.info',
        'TypeScript: admin UI': 'build/coverage/typescript/lcov.info',
        'Python: Quickstart, SDK and tooling': 'build/coverage/python.xml',
        'Rust: Cargo workspace': 'build/coverage/rust.lcov',
    }
    for scope, name in reports.items():
        lines.append(f'| {scope} | {"Available" if (root/name).exists() else "MISSING — see collector logs"} |')
    aggregate = root / reports['Java: all root Gradle modules']
    if aggregate.exists():
        counters = {c.attrib['type']: c.attrib for c in ET.parse(aggregate).getroot().findall('counter')}
        lines += ['', '### All compiled Java production classes', '']
        for kind in ('LINE', 'BRANCH'):
            counter = counters.get(kind, {})
            covered, missed = int(counter.get('covered', 0)), int(counter.get('missed', 0))
            value = f'{100*covered/(covered+missed):.2f}%' if covered+missed else 'N/A'
            lines.append(f'{kind}: {value} ({covered} covered, {missed} missed).')
    lines += ['', '### Java module completeness', '', '| Module | Classes compiled | Test report |', '| --- | --- | --- |']
    import re
    for name in re.findall(r'project\("[^\"]+"\)\.projectDir = file\("([^\"]+)"\)', (root/'settings.gradle.kts').read_text()):
        classes = list((root/name/'build/classes').glob('**/*.class'))
        report = root/name/'build/reports/jacoco/test/jacocoTestReport.xml'
        lines.append(f'| {name} | {len(classes)} | {"Available" if report.exists() else "No module report; compiled production classes included in aggregate" if classes else "MISSING: not compiled"} |')
    lines += ['', '### Remaining measurement gaps', '', 'Independent Java example/core-MCP builds, shell/PowerShell, PHP and C# probes are not instrumented by these collectors. They are not included in a repository-wide percentage.', '', '### Explicit exclusions', '', 'Tests, test fixtures, generated output, frozen compatibility evidence and TypeScript declarations are excluded from production coverage. Documentation and configuration are not executable source. See `source-inventory.json` for tracked source candidates and exclusions.', '', 'Aggregate Java coverage includes compiled production classes with no test execution as 0%. If a collector fails or compilation is incomplete, this baseline is partial. No thresholds are enforced.']
    return '\n'.join(lines) + '\n'
