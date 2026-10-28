# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Normalize UI LCOV paths to the root used by the Sonar scanner."""
from pathlib import Path


def normalize(text):
    lines = []
    for line in text.splitlines():
        if line.startswith('SF:'):
            name = line[3:].replace('\\', '/')
            if name.startswith('src/'):
                name = 'apps/admin-ui/' + name
            line = 'SF:' + name
        lines.append(line)
    return '\n'.join(lines) + '\n'


if __name__ == '__main__':
    report = Path('build/coverage/typescript/lcov.info')
    if report.exists():
        report.write_text(normalize(report.read_text()), encoding='utf-8')
