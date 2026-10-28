# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Collect Python coverage without hiding later suites after an earlier failure."""
from pathlib import Path
import subprocess
import sys

suites = ['tests/ci', 'tests/contracts', 'tests/quickstart', 'tool-sdk/python-sdk/tests']
status = 0
for suite in suites:
    result = subprocess.run([sys.executable, '-m', 'coverage', 'run', '--parallel-mode', '-m', 'unittest', 'discover', '-s', suite])
    status = status or result.returncode
for args in (['combine'], ['xml'], ['html', '-d', 'build/coverage/python-html']):
    result = subprocess.run([sys.executable, '-m', 'coverage', *args])
    status = status or result.returncode
sys.exit(status)
