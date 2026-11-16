# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Drop a local issue log into the configured device hot folder and await its result."""
import argparse
import json
from pathlib import Path
import secrets
import subprocess
import time

import manage
import trigger_e2e


def submit(path, timeout=240):
    content = path.read_bytes()
    if not content or len(content) > 16384 or b'\0' in content:
        raise ValueError('An issue log must contain 1–16384 UTF-8 bytes without NUL')
    content.decode('utf-8')
    name = 'issue-' + secrets.token_hex(8) + '.txt'
    source = 'inbox/' + name
    subprocess.run(trigger_e2e.COMPOSE + ['exec', '-T', 'linux-device', 'sh', '-c',
        f'umask 077 && cat > {trigger_e2e.FOLDER}/{source}'], input=content,
        check=True, capture_output=True, cwd=manage.HERE, timeout=15)
    print('Submitted ' + source, flush=True)
    deadline = time.monotonic() + timeout
    directory = manage.STATE / 'workflow/results'
    while time.monotonic() < deadline:
        for log in directory.glob('*.json'):
            try:
                result = json.loads(log.read_text(encoding='utf-8'))
            except (OSError, ValueError):
                continue  # A concurrent writer has not completed its private result file.
            if isinstance(result.get('result'), dict) and result['result'].get('sourcePath') == source:
                if result.get('state') != 'SUCCEEDED':
                    raise ValueError('Workflow did not succeed; inspect the worker metadata logs')
                return {**result, 'resultLog': str(log)}
        time.sleep(1)
    raise ValueError('No confirmed result before the deadline; inspect the listener and worker logs. Do not retry effects automatically.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('issue_log', type=Path)
    args = parser.parse_args()
    manage.load_environment()
    try:
        print(json.dumps(submit(args.issue_log), indent=2))
    except (ValueError, OSError, subprocess.SubprocessError) as failure:
        parser.exit(1, str(failure) + '\n')


if __name__ == '__main__':
    main()
