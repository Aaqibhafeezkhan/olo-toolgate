# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Refresh mounted short-lived credentials for the isolated local workflow demo IdP."""
import argparse
import os
from pathlib import Path
import secrets
import subprocess
import time

import manage


def replace_credential(path, value):
    temporary = path.with_name(path.name + '.next-' + secrets.token_hex(6))
    try:
        manage.shared.private_file(temporary, value)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def prepare_results():
    directory = manage.STATE / 'workflow/results'
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    if os.name == 'nt':
        result = subprocess.run(['powershell.exe', '-NoProfile', '-NonInteractive', '-Command',
            "$ErrorActionPreference='Stop';$user=[System.Security.Principal.WindowsIdentity]::GetCurrent().User;"
            "$acl=New-Object System.Security.AccessControl.DirectorySecurity;$acl.SetAccessRuleProtection($true,$false);"
            "foreach($sid in @($user,(New-Object System.Security.Principal.SecurityIdentifier('S-1-5-18')))){"
            "$acl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule($sid,'FullControl',"
            "'ContainerInherit,ObjectInherit','None','Allow')))};"
            "[System.IO.Directory]::SetAccessControl($env:TOOLGATE_WORKFLOW_RESULT_DIRECTORY,$acl)"],
            env={**os.environ, 'TOOLGATE_WORKFLOW_RESULT_DIRECTORY': str(directory)}, capture_output=True)
        if result.returncode:
            raise ValueError('Cannot protect local workflow results')
    else:
        directory.chmod(0o700)


def refresh(config=None):
    config = config or manage.Configuration(manage.wait_ready(), True)
    if not config.origin.startswith('http://127.0.0.1:'):
        raise ValueError('The fixture identity helper requires a local Quickstart')
    custody = manage.STATE / 'workflow/worker'
    custody.mkdir(mode=0o700, parents=True, exist_ok=True)
    ca = (manage.STATE / 'agents/gateway-ca.crt').read_text(encoding='ascii')
    manage.shared.private_file(custody / 'gateway-ca.crt', ca)
    token = manage.docker('quickstart', ['/opt/quickstart-python/bin/python', '-c',
        "import sys;sys.path.insert(0,'/opt/quickstart');import supervisor as s;"
        "print(s.jwt(['toolgate-workflow-runtime'],'workflow-e2e',directory_bound=False))"])
    prepare_results()
    replace_credential(custody / 'worker.token', token)
    replace_credential(custody / 'listener.token', config.tokens['admin'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--once', action='store_true')
    args = parser.parse_args()
    manage.load_environment()
    pid = manage.STATE / 'workflow/identity.pid'
    manage.shared.private_file(pid, str(os.getpid()))
    try:
        while True:
            refresh()
            print('Local workflow fixture credentials refreshed', flush=True)
            if args.once:
                return 0
            time.sleep(300)
    except KeyboardInterrupt:
        return 130
    except Exception:
        # Login/transport failures can contain private values; never copy them into a log.
        print('WORKFLOW_FIXTURE_IDENTITY_STOPPED', flush=True)
        return 2
    finally:
        if pid.exists() and pid.read_text().strip() == str(os.getpid()):
            pid.unlink()


if __name__ == '__main__':
    raise SystemExit(main())
