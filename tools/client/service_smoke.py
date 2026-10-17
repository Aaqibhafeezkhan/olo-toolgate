# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Native CI-only service-manager proof: no login task, installer process or interactive terminal dependency."""
import argparse
import json
import os
import platform
import subprocess
import time
from pathlib import Path

def run(arguments):
    result=subprocess.run(arguments,capture_output=True,text=True)
    if result.returncode:
        # The failing command's own output, so a red run names its cause.
        shown=arguments[2:] if arguments[:2]==['sudo','-n'] else arguments
        print(f'{shown} exited {result.returncode}\n{result.stdout}{result.stderr}',flush=True)
        raise subprocess.CalledProcessError(result.returncode,arguments,result.stdout,result.stderr)
    return result
def check(binary):
    if os.environ.get('GITHUB_ACTIONS')!='true':raise RuntimeError('Service installation smoke is restricted to disposable native CI runners')
    system=platform.system();elevate=[] if system=='Windows' else ['sudo','-n']
    command=[*elevate,str(binary.resolve())];installed=False
    try:
        run([*command,'install','--server','https://control.example.invalid']);installed=True
        deadline=time.monotonic()+45
        while True:
            try:
                first=json.loads(run([str(binary.resolve()),'health']).stdout);break
            except (subprocess.CalledProcessError,json.JSONDecodeError):
                if time.monotonic()>deadline:raise
                time.sleep(.2)
        assert not first['ready'] and first['state']=='UNENROLLED'
        # The installer and first CLI already exited. Service lifetime belongs to the system manager.
        if system=='Linux':
            value=run(['systemctl','show','olo-toolgate-client','--property=User,ActiveState,MainPID']).stdout
            assert 'User=root' in value and 'ActiveState=active' in value
            run(['systemctl','is-enabled','olo-toolgate-client'])
        elif system=='Darwin':
            value=run(['sudo','-n','launchctl','print','system/io.ololabs.toolgate.client']).stdout
            assert 'state = running' in value
        else:
            value=run(['sc.exe','qc','OloToolGateClient']).stdout
            assert 'LocalSystem' in value and 'AUTO_START' in value
            assert 'RUNNING' in run(['sc.exe','query','OloToolGateClient']).stdout
        second=json.loads(run([str(binary.resolve()),'health']).stdout)
        assert second['uptimeSeconds']>=first['uptimeSeconds'] and second['state']=='UNENROLLED'
        print('System service remains live after installer and caller exits; unauthenticated protected mode remains unready')
    finally:
        if installed:run([*command,'uninstall','--purge'])

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--binary',type=Path,required=True);check(parser.parse_args().binary)
