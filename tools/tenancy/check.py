# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Qualify live tenant routing, device identity and key rotation in an isolated local image."""
import argparse
import json
from pathlib import Path
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]


def run(*arguments, **options):
    return subprocess.run(['docker', *arguments], check=True, text=True, capture_output=True, **options)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', default='ololab/olo-toolgate-quickstart:dev')
    parser.add_argument('--control-app', type=Path, help='Matching packaged quarkus-app directory; otherwise use image contents')
    parser.add_argument('--gateway-binary', type=Path, help='Matching Linux Gateway binary; otherwise use image contents')
    parser.add_argument('--output', type=Path, default=ROOT/'.dev/tenant-runtime-evidence.json')
    args = parser.parse_args()
    for path, directory in [(args.control_app, True), (args.gateway_binary, False)]:
        if path is not None and not (path.is_dir() if directory else path.is_file()):
            parser.error('A supplied runtime artifact is missing')
    # Only this invocation's fresh names can be cleaned up. No existing application volumes are read.
    volume = 'toolgate-tenant-check-'+uuid.uuid4().hex
    container = None
    run('volume', 'create', volume)
    try:
        mounts = []
        sources = [
            (ROOT/'tools/tenancy/runtime_fixture.py', '/tmp/runtime-fixture.py'),
            (ROOT/'apps/quickstart/supervisor.py', '/opt/quickstart/supervisor.py'),
            (ROOT/'apps/quickstart/enterprise_bootstrap.py', '/opt/quickstart/enterprise_bootstrap.py'),
            (ROOT/'tools/enterprise/restore.py', '/opt/quickstart/enterprise_restore.py'),
        ]
        if args.control_app is not None:
            sources.append((args.control_app.resolve(), '/app'))
        if args.gateway_binary is not None:
            sources.append((args.gateway_binary.resolve(), '/usr/local/bin/olo-toolgate-gateway'))
        for source, target in sources:
            mounts += ['-v', source.as_posix()+':'+target+':ro']
        command = ['run', '--pull=never', '-d', '--network', 'none', '--memory=1800m', '--cpus=3']
        for host in ['tenant-a.example.test', 'tenant-b.example.test', 'unknown.example.test']:
            command += ['--add-host', host+':127.0.0.1']
        command += ['-v', volume+':/data', *mounts,
                    '-e', 'TOOLGATE_CONTROL_BUILDER_ENABLED=false',
                    '-e', 'TOOLGATE_CONTROL_CORE_PACKAGES_LOCK_PATH=',
                    '-e', 'TOOLGATE_CONTROL_PROFILE_PATH=',
                    '--entrypoint', '/opt/quickstart-python/bin/python', args.image, '/tmp/runtime-fixture.py']
        container = run(*command).stdout.strip()
        deadline = time.monotonic()+480
        while True:
            state = json.loads(run('inspect', '--format', '{{json .State}}', container).stdout)
            if not state['Running']:
                logs = run('logs', '--tail', '20', container)
                print(logs.stdout, end='')
                if state['ExitCode'] != 0:
                    raise RuntimeError('Tenant runtime fixture failed: '+logs.stderr[-2000:])
                break
            if time.monotonic() >= deadline:
                raise TimeoutError('Tenant runtime fixture exceeded its deadline')
            time.sleep(1)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        run('cp', container+':/data/evidence.json', str(args.output.resolve()))
        print('Public signed-discovery/certificate evidence: '+str(args.output.resolve()))
    finally:
        if container:
            subprocess.run(['docker', 'rm', '-f', container], capture_output=True)
        subprocess.run(['docker', 'volume', 'rm', volume], capture_output=True)


if __name__ == '__main__':
    main()
