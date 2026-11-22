# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Select the runner's installed JDK without downloading a setup action or JDK."""
import os
from pathlib import Path
import re
import subprocess
import sys


def main():
    # Hosted Ubuntu runners expose the versioned location. Local replay images
    # may instead supply JAVA_HOME; validate it with exactly the same checks.
    location = os.environ.get('JAVA_HOME_21_X64') or os.environ.get('JAVA_HOME')
    if not location or any(character in location for character in '\r\n'):
        raise SystemExit('Set JAVA_HOME_21_X64 (or local JAVA_HOME) to an installed JDK 21')
    home = Path(location).resolve()
    suffix = '.exe' if os.name == 'nt' else ''
    for tool, pattern in (
        ('java', r'(?m)^\s*java\.specification\.version\s*=\s*21\s*$'),
        ('javac', r'(?m)^javac 21(?:[.\s]|$)'),
    ):
        executable = home / 'bin' / (tool + suffix)
        if not executable.is_file():
            raise SystemExit(f'JDK 21 executable missing: {executable}')
        arguments = ['-XshowSettings:properties', '-version'] if tool == 'java' else ['-version']
        result = subprocess.run([str(executable), *arguments], check=True,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, timeout=30)
        if not re.search(pattern, result.stdout):
            raise SystemExit(f'{executable} must report Java 21; got:\n{result.stdout}')
    # Publish only after both executables pass; never silently select default 17.
    with Path(os.environ['GITHUB_ENV']).open('a', encoding='utf-8') as output:
        output.write(f'JAVA_HOME={home}\n')
    with Path(os.environ['GITHUB_PATH']).open('a', encoding='utf-8') as output:
        output.write(f'{home / "bin"}\n')
    print(f'Selected installed JDK 21: {home}')


if __name__ == '__main__':
    try:
        main()
    except (OSError, subprocess.SubprocessError) as error:
        sys.exit(f'Cannot select installed JDK 21: {error}')
