# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Run the local Java tenant operator with separate protected platform configuration."""
import argparse
import os
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--app', type=Path, required=True)
    parser.add_argument('--connection', type=Path, required=True)
    parser.add_argument('command', choices=['preview', 'provision', 'inspect', 'suspend', 'resume', 'publish-identity', 'capacity'])
    parser.add_argument('target', help='Manifest/publication path or registered alias')
    parser.add_argument('--expected-uuid', help='Required for suspend/resume')
    parser.add_argument('--limits-file',type=Path,help='Protected capacity limits JSON')
    args = parser.parse_args()
    if (args.command in ('suspend', 'resume') or args.command=='capacity' and args.limits_file is not None) != bool(args.expected_uuid):
        parser.error('Lifecycle and capacity updates require --expected-uuid')
    if args.limits_file is not None and args.command!='capacity':parser.error('--limits-file is for capacity updates')
    roots = [args.app/'app', args.app/'lib/main', args.app/'lib/boot']
    if not all(root.is_dir() for root in roots):
        parser.error('A matching packaged quarkus-app directory is required')
    classpath = os.pathsep.join(str(root.resolve()/'*') for root in roots)
    command = ['java', '-cp', classpath, 'io.ololabs.toolgate.control.adapter.TenantOperator',
               args.command, str(args.connection.resolve()), args.target]
    if args.expected_uuid:
        command.append(args.expected_uuid)
    if args.limits_file is not None:command.append(str(args.limits_file.resolve()))
    raise SystemExit(subprocess.run(command).returncode)


if __name__ == '__main__':
    main()
