# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Read-only, standard-library metadata gates before expensive CI builds/downloads."""
from pathlib import Path
import os
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]


def main():
    ref = os.environ.get('GITHUB_REF', '')
    if ref.startswith('refs/tags/') and ref != 'refs/tags/v'+(ROOT/'VERSION').read_text(encoding='utf-8').strip():
        print('Release tag must match v<VERSION>; no build or publication started', file=sys.stderr)
        return 1
    for script in ('tools/contracts/version.py', 'tools/contracts/generate.py'):
        result = subprocess.run([sys.executable, str(ROOT/script), '--check'], cwd=ROOT)
        if result.returncode:
            print('Repair generated metadata locally, then commit all outputs:\n'
                  '  python tools/contracts/version.py\n'
                  '  python tools/contracts/generate.py\n'
                  '  python tools/ci/preflight.py', file=sys.stderr)
            return result.returncode
    print('Early release metadata and generated-binding gates passed')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
