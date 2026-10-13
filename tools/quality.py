# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Source license/header and obvious-secret gate; full scanners run in CI."""
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE_SUFFIXES = {'.java','.rs','.ts','.tsx','.css','.html','.js','.mjs','.php','.py','.sh','.kts','.toml','.ps1','.yml','.yaml','.sql','.properties'}
SECRET_PATTERNS = [
    re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'),
    re.compile(r'\bAKIA[0-9A-Z]{16}\b'),
    re.compile(r'\bgh[pousr]_[A-Za-z0-9]{36,}\b'),
]
# Paths whose files are rewritten whole by other tools, so a header would not survive. The Drupal config that needed
# this moved to olo-marketplace.
EXPORTED = ()


def violations(path, text, exported=False):
    problems = []
    if not exported and (path.suffix in SOURCE_SUFFIXES or path.name in ('Makefile','gradlew','gradlew.bat','gradle.properties')):
        header = text[:600]
        if 'GENERATED FILE' not in header and not ('Copyright 2026 OLO Labs' in header and 'SPDX-License-Identifier: Apache-2.0' in header):
            problems.append('missing copyright/SPDX header')
    if any(pattern.search(text) for pattern in SECRET_PATTERNS):
        problems.append('potential secret (redacted)')
    return problems


def main():
    files = subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard','-z'], cwd=ROOT).decode().split('\0')
    problems = []
    for name in sorted(set(files)-{''}):
        path = ROOT/name
        if not path.is_file(): continue
        try: content = path.read_text(encoding='utf-8')
        except UnicodeDecodeError: continue
        for problem in violations(path, content, name.startswith(EXPORTED)):
            problems.append(f'{name}: {problem}')
    lock = json.loads((ROOT/'package-lock.json').read_text())
    allowed = {'Apache-2.0','MIT','MIT-0','CC0-1.0','BlueOak-1.0.0','BSD-2-Clause','BSD-3-Clause','ISC','0BSD'}
    for name, package in lock['packages'].items():
        if name and not package.get('link') and package.get('license') not in allowed and not (package.get('dev') and package.get('license') == 'MPL-2.0'):
            problems.append(f'npm license requires review: {name}')
    if problems: raise SystemExit('\n'.join(problems))
    print('Source headers, npm licenses and obvious-secret checks passed')


if __name__ == '__main__': main()
