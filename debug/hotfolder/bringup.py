#!/usr/bin/env python3
# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Start or exercise the isolated, bind-mounted HotFolder example on a Linux Docker host."""
import argparse
import os
from pathlib import Path
import re
import stat
import subprocess
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
EXAMPLE = ROOT / 'examples/langchain'
PRIVATE = ROOT / '.dev/debug/hotfolder'
FILES = PRIVATE / 'files'
STATE = PRIVATE / 'state'
IMAGE = 'olo-toolgate-quickstart:debug'
PROJECT = 'toolgate-hotfolder'
HTTP = '18092'

# The helper can only see this example's mount. It never fixes an existing
# directory's ownership and never overwrites a file dropped into the inbox.
MOUNT_HELPER = '''
import os, stat, sys
from pathlib import Path
root = Path('/files')
operation = sys.argv[1]
if operation == 'initialize':
    if any(root.iterdir()): raise ValueError('New mount is not empty')
    root.chmod(0o700)
    os.chown(root, 0, 0)
    for name in ('inbox', 'processed'): (root / name).mkdir(mode=0o700)
for path in (root, root / 'inbox', root / 'processed'):
    info = path.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != 0 or stat.S_IMODE(info.st_mode) != 0o700:
        raise ValueError('Mounted files area must have root-owned mode 0700 directories')
if operation == 'drop':
    data = sys.stdin.buffer.read(65537)
    if len(data) > 65536: raise ValueError('File exceeds the device limit')
    fd = os.open(root / 'inbox' / sys.argv[2], os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'wb') as output: output.write(data)
'''


def run(args, **options):
    return subprocess.run(args, cwd=ROOT, check=True, **options)


def mount_helper(operation, *args, data=None):
    if FILES.resolve() != FILES or ',' in str(FILES):
        raise ValueError('Use the real repository path without symlinks in the HotFolder state path')
    run(['docker', 'run', '--rm', '-i', '--pull', 'never', '--network', 'none', '--user', '0:0',
         '--security-opt', 'no-new-privileges:true', '--mount', f'type=bind,source={FILES},target=/files',
         '--entrypoint', '/opt/quickstart-python/bin/python', IMAGE, '-c', MOUNT_HELPER, operation, *args], input=data)


def mount_ready():
    if PRIVATE.resolve() != PRIVATE or FILES.resolve() != FILES:
        raise ValueError('Use the real repository path without symlinks in the HotFolder state path')
    PRIVATE.mkdir(mode=0o700, parents=True, exist_ok=True)
    created = not FILES.exists()
    if created:
        FILES.mkdir(mode=0o700)
    mount_helper('initialize' if created else 'check')
    info = FILES.lstat()
    if info.st_uid != 0 or stat.S_IMODE(info.st_mode) != 0o700:
        raise ValueError('The selected Docker engine does not preserve the host mount ownership and mode')


def drop(name, text):
    if not re.fullmatch(r'[a-z0-9][a-z0-9_-]{0,60}\.txt', name):
        raise ValueError('Use a lowercase .txt file name without directories')
    mount_helper('drop', name, data=text.encode('utf-8'))
    print(f'Dropped {FILES / "inbox" / name}', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    start = commands.add_parser('start')
    start.add_argument('--prepared', action='store_true', help='Reuse this lab’s previously prepared package and registry inputs')
    for command in ('verify', 'listen', 'status', 'stop'):
        commands.add_parser(command)
    writer = commands.add_parser('drop')
    writer.add_argument('name')
    writer.add_argument('text')
    args = parser.parse_args()
    if os.name != 'posix':
        raise ValueError('Run this script from Linux or WSL with a Linux filesystem bind mount')
    os.umask(0o077)
    os.environ.update(TOOLGATE_LANGCHAIN_PROJECT=PROJECT, TOOLGATE_LANGCHAIN_STATE=str(STATE),
                      TOOLGATE_QUICKSTART_IMAGE=IMAGE, TOOLGATE_HTTP_PORT=HTTP, TOOLGATE_TLS_PORT='18452',
                      TOOLGATE_REGISTRY_PORT='18553', TOOLGATE_HOTFOLDER_ROOT=str(ROOT), TOOLGATE_HOTFOLDER_FILES=str(FILES),
                      TOOLGATE_GATEWAY_NAME='Mounted HotFolder Gateway')
    compose = ['docker', 'compose', '-p', PROJECT, '-f', str(EXAMPLE/'compose.yaml'),
               '-f', str(EXAMPLE/'compose.triggers.yaml'), '-f', str(HERE/'compose.yaml')]
    if args.command != 'drop':
        run(['docker', 'compose', 'version'], stdout=subprocess.DEVNULL)
    if args.command in {'status', 'stop'}:
        run(compose + ['ps' if args.command == 'status' else 'stop'])
        return
    if args.command == 'drop':
        drop(args.name, args.text)
        return
    sys.path.insert(0, str(EXAMPLE))
    import manage
    manage.COMPOSE = compose
    if args.command == 'start':
        run(['docker', 'image', 'inspect', IMAGE], stdout=subprocess.DEVNULL)
        mount_ready()
        STATE.mkdir(mode=0o700, exist_ok=True)
        if not args.prepared:
            manage.packages.prepare(compose, STATE, os.environ)
        else:
            for required in ('client-images.env', 'package/core-packages.lock.json', 'package/hotfolder.tgpkg'):
                if not (STATE / required).is_file():
                    raise ValueError('Prepared input missing; run start without --prepared')
        run(['docker', 'build', '-f', 'debug/standard/linux-device.Dockerfile', '-t', 'olo-toolgate-debug-device:hotfolder', '.'])
        run(compose + ['up', '-d', '--build', '--wait', '--wait-timeout', '180', 'quickstart', 'linux-device'])
        manage.provision(argparse.Namespace(bootstrap_local=True, approve_linux_device=True, target='linux', device_id=None))
        print(f'Console: http://127.0.0.1:{HTTP}/console/\nMounted files: {FILES}\nRun verify before listen.')
    elif args.command == 'verify':
        import trigger_e2e
        trigger_e2e.COMPOSE = compose
        trigger_e2e.main(drop=drop)
    elif args.command == 'listen':
        import trigger_agent
        sys.argv = [sys.argv[0], '--target', 'linux']
        trigger_agent.main()


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        raise SystemExit(130)
    except subprocess.CalledProcessError as failure:
        print(f'HotFolder command failed (exit {failure.returncode}); see its diagnostic above.', file=sys.stderr)
        raise SystemExit(1)
    except (OSError, ValueError, KeyError, subprocess.SubprocessError, AssertionError) as failure:
        print(f'HotFolder: {failure}', file=sys.stderr)
        raise SystemExit(1)
