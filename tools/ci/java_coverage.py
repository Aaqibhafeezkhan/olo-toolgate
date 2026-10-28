# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Run Java coverage with the same isolated database used by the Java CI gates."""
import os
from pathlib import Path
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from control.check import database

if __name__ == '__main__':
    command = ['gradlew.bat' if os.name == 'nt' else './gradlew', '--no-daemon', '--no-configuration-cache']
    with database() as db:
        env = dict(os.environ, **{key: db[key] for key in ('CONTROL_TEST_URL', 'CONTROL_TEST_PASSWORD')})
        tests = subprocess.run(command + ['--continue', 'test', 'jacocoTestReport'], env=env)
        report = subprocess.run(command + ['jacocoRootReport'], env=env)
    sys.exit(tests.returncode or report.returncode)
