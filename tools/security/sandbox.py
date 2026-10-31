# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Execute mandatory rootless OCI attack tests in a delegated systemd service.

The image must already be prepared for the service user. This runner neither
pulls images nor changes controller delegation. A missing prerequisite fails.
"""
import argparse
import hashlib
import json
import os
import platform
from pathlib import Path
import re
import shutil
import subprocess
import sys
import uuid

ROOT = Path(__file__).resolve().parents[2]
GIT = ['git', '-c', 'safe.directory=' + str(ROOT)]


def source_digest():
    names = subprocess.check_output(GIT + ['ls-files', '--cached', '--others', '--exclude-standard', '-z'], cwd=ROOT)
    digest = hashlib.sha256()
    for name in sorted(set(names.split(b'\0')) - {b''}):
        path = ROOT / os.fsdecode(name)
        if path.is_file():
            digest.update(name + b'\0' + hashlib.sha256(path.read_bytes()).digest())
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', required=True, help='already prepared name@sha256:digest')
    parser.add_argument('--service-user', help='root launches this unprivileged service user; otherwise use the user manager')
    parser.add_argument('--cargo', default=shutil.which('cargo'))
    parser.add_argument('--io-device', help='major:minor backing container storage; defaults to the service home device')
    args = parser.parse_args()
    if sys.platform != 'linux':
        parser.error('Linux with systemd, rootless Podman/crun and cgroup v2 is required')
    import pwd
    account = pwd.getpwnam(args.service_user) if args.service_user else pwd.getpwuid(os.getuid())
    if account.pw_uid == 0 or (args.service_user and os.geteuid() != 0):
        parser.error('the worker service must be unprivileged; --service-user requires an administrator')
    if not re.fullmatch(r'[A-Za-z0-9./_:-]+@sha256:[a-f0-9]{64}', args.image):
        parser.error('a fully qualified immutable image reference is required')
    if not args.cargo or not Path(args.cargo).is_absolute():
        parser.error('an absolute cargo executable is required')
    device = os.stat(account.pw_dir).st_dev
    io_device = args.io_device or f'{os.major(device)}:{os.minor(device)}'
    if not re.fullmatch(r'[0-9]+:[0-9]+', io_device) or io_device.startswith('0:'):
        parser.error('a real backing block device is required; supply --io-device for overlay filesystems')
    unit = 'toolgate-sandbox-test-' + uuid.uuid4().hex[:12]
    command = ['systemd-run', '--collect', '--wait', '--pipe', '--unit=' + unit,
               '--property=Delegate=yes', '--property=DelegateSubgroup=supervisor',
               '--property=KillMode=control-group', '--working-directory=' + str(ROOT)]
    if args.service_user:
        command += ['--uid=' + account.pw_name, '--gid=' + str(account.pw_gid)]
        parent = '/system.slice/' + unit + '.service'
    else:
        command += ['--user']
        manager = subprocess.check_output(['systemctl', '--user', 'show', '--property=ControlGroup', '--value'], text=True).strip()
        parent = manager + '/app.slice/' + unit + '.service'
    environment = {'HOME': account.pw_dir, 'XDG_RUNTIME_DIR': f'/run/user/{account.pw_uid}',
                   'PATH': os.environ.get('PATH', '/usr/local/bin:/usr/bin:/bin'),
                   'TOOLGATE_SANDBOX_IMAGE': args.image, 'TOOLGATE_SANDBOX_IO_DEVICE': io_device,
                   'TOOLGATE_SANDBOX_CGROUP_PARENT': parent, 'CARGO_TERM_COLOR': 'never'}
    for name in ('RUSTUP_HOME', 'CARGO_HOME'):
        if name in os.environ:
            environment[name] = os.environ[name]
    command += ['--setenv=' + name + '=' + value for name, value in environment.items()]
    command += [args.cargo, 'test', '-p', 'olo-toolgate-tool-host', '--lib', '--test', 'sandbox', '--test', 'dispatch',
                '--locked', '--', '--ignored', '--test-threads=1']
    output = ROOT / 'build/security/sandbox'
    output.mkdir(parents=True, exist_ok=True)
    before = source_digest()
    result = subprocess.run(command, cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    print(result.stdout, end='')
    (output / 'conformance.log').write_text(result.stdout, encoding='utf-8')
    passed = re.findall(r'^test (\S+) \.\.\. ok$', result.stdout, re.MULTILINE)
    success = (result.returncode == 0 and len(passed) >= 6
               and 'a_pinned_sdk_package_runs_in_oci_and_lease_revocation_tears_it_down' in passed
               and before == source_digest())
    report = {'revision': subprocess.check_output(GIT + ['rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
              'sourceSha256': before, 'image': args.image, 'ioDevice': io_device,
              'tier': 'OCI_SANDBOX', 'architecture': platform.machine(), 'passed': success, 'executedTests': passed,
              'scope': 'Python rootless worker containment; no per-effect authorization or compromised-kernel guarantee'}
    (output / 'conformance.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    return 0 if success else 1


if __name__ == '__main__':
    raise SystemExit(main())
