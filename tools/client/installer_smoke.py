# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Exercise packaged installers only on disposable native CI runners."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tempfile
import time
from package import ROOT


def run(arguments):return subprocess.run(arguments,check=True,capture_output=True,text=True)


def check(target):
    if os.environ.get('GITHUB_ACTIONS')!='true':raise RuntimeError('Disposable native CI only')
    system=platform.system();suffix={'Windows':'setup.exe','Darwin':'dmg','Linux':'run'}[system]
    version=(ROOT/'VERSION').read_text().strip()
    installer=ROOT/f'build/client/release/olo-toolgate-client-{version}-{target}.{suffix}'
    if installer.with_name(installer.name+'.sha256').read_text().strip()!=hashlib.sha256(installer.read_bytes()).hexdigest()+'  '+installer.name:raise ValueError('Installer checksum mismatch')
    binary=ROOT/'target/release'/('olo-toolgate-client.exe' if system=='Windows' else 'olo-toolgate-client')
    installed=False
    try:
        if system=='Windows':
            run(['powershell.exe','-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass',
                 '-STA','-File',str(ROOT/'tools/client/tray_status_test.ps1')])
            # A fresh install keeps the HTTPS default without Chrome or a console lookup.
            run([str(installer),'/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART'])
            installed=True
        elif system=='Linux':
            run(['/bin/sh',str(installer),'--verify'])
            run(['sudo','-n','/bin/sh',str(installer),'--elevated','https://control.example.invalid',str(os.getuid())])
            installed=True
        else:
            # The native GUI app is compiled into the disk image. Execute its exact payload
            # headlessly; administrator dialogs require an interactive desktop test.
            with tempfile.TemporaryDirectory(prefix='installer-mount-') as temporary:
                run(['/usr/bin/hdiutil','attach',str(installer),'-readonly','-nobrowse','-mountpoint',temporary])
                try:
                    source=Path(temporary)/'Install ToolGate.app/Contents/Resources/payload/olo-toolgate-client'
                    if hashlib.sha256(source.read_bytes()).digest()!=hashlib.sha256(binary.read_bytes()).digest():raise ValueError('Disk image native payload drift')
                    run(['sudo','-n',str(source),'install','--server','https://control.example.invalid']);installed=True
                finally:run(['/usr/bin/hdiutil','detach',temporary])
        deadline=time.monotonic()+45
        while True:
            try:
                health=json.loads(run([str(binary),'health']).stdout)
                assert health['state']=='UNENROLLED' and not health['ready']
                break
            except subprocess.CalledProcessError:
                if time.monotonic()>deadline:raise
                time.sleep(.2)
        if system=='Windows':assert 'RUNNING' in run(['sc.exe','query','OloToolGateClient']).stdout
        elif system=='Linux':assert run(['systemctl','is-active','olo-toolgate-client']).stdout.strip()=='active'
        else:assert 'state = running' in run(['sudo','-n','launchctl','print','system/io.ololabs.toolgate.client']).stdout
        if system=='Windows':
            import winreg
            config=Path(r'C:\ProgramData\OLO\ToolGate\client.json')
            if json.loads(config.read_text())['serverUrl']!='https://localhost:18450':raise ValueError('Installer HTTPS default was not stored')
            state=config.parent/'state'
            device_key_digest=hashlib.sha256((state/'device-key').read_bytes()).hexdigest()
            # Configured anonymous web downloads still work without a URL argument.
            hint='https://control.example.invalid'.encode().hex()
            with tempfile.TemporaryDirectory(prefix='toolgate-configured-') as temporary:
                configured=Path(temporary)/f'olo-toolgate-client-{target}--{hint}.setup.exe'
                shutil.copyfile(installer,configured)
                run([str(configured),'/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART'])
            run([str(installer),'/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART'])
            if hashlib.sha256((state/'device-key').read_bytes()).hexdigest()!=device_key_digest:raise ValueError('Repair changed the device key')
            run([str(binary),'configure','--server','https://second.example.invalid'])
            if json.loads(config.read_text())['serverUrl']!='https://second.example.invalid':raise ValueError('Gateway switch was not stored')
            if hashlib.sha256((state/'device-key').read_bytes()).hexdigest()!=device_key_digest:raise ValueError('Gateway switch changed the device key')
            with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE,r'Software\Microsoft\Windows\CurrentVersion\Run',0,winreg.KEY_READ|winreg.KEY_WOW64_64KEY) as tray_key:
                if 'toolgate-tray.ps1' not in winreg.QueryValueEx(tray_key,'OloToolGateTray')[0]:raise ValueError('Tray startup is missing')
            powershell=str(Path(os.environ['SystemRoot'])/'System32/WindowsPowerShell/v1.0/powershell.exe')
            tray_query="@(Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'powershell.exe' -and $_.ProcessId -ne $PID -and $_.CommandLine -like '*-File*toolgate-tray.ps1*' }).Count"
            time.sleep(3)
            if int(run([powershell,'-NoProfile','-NonInteractive','-Command',tray_query]).stdout.strip())<1:raise ValueError('Installed tray did not stay running')
            with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE,r'Software\Google\Chrome\NativeMessagingHosts\io.ololabs.toolgate.connect',0,winreg.KEY_READ|winreg.KEY_WOW64_64KEY) as key:
                registered=Path(winreg.QueryValue(key,None))
            host_manifest=json.loads(registered.read_text())
            allowed='chrome-extension://emmemldedebhbloibichmmdlbpjakfkf/'
            if host_manifest['allowed_origins']!=[allowed] or host_manifest['name']!='io.ololabs.toolgate.connect':raise ValueError('Native host registration drift')
            host=Path(host_manifest['path'])
            request=b'{"operation":"health"}'
            frame=len(request).to_bytes(4,'little')+request
            result=subprocess.run([str(host),allowed],input=frame,capture_output=True,timeout=25,check=True)
            if len(result.stdout)<4 or int.from_bytes(result.stdout[:4],'little')!=len(result.stdout)-4:raise ValueError('Native frame corruption')
            browser_health=json.loads(result.stdout[4:])
            if browser_health.get('error') or browser_health['health']['state']!='UNENROLLED':raise ValueError('Native host cannot reach protected service')
            denied=subprocess.run([str(host),'chrome-extension://'+'a'*32+'/'],input=frame,capture_output=True,timeout=25)
            if denied.returncode==0 or denied.stdout:raise ValueError('Wrong Chrome extension accepted')
        print('Packaged installer started the system service; protected mode remains unenrolled')
    finally:
        if installed:
            if system=='Windows':
                # Reinstall may create unins001.exe; use Windows' active registration.
                with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r'Software\Microsoft\Windows\CurrentVersion\Uninstall\OLO.ToolGate.Client_is1', 0, winreg.KEY_READ|winreg.KEY_WOW64_64KEY) as registration:
                    uninstaller=Path(winreg.QueryValueEx(registration,'UninstallString')[0].strip('"'))
                expected=Path(os.environ['ProgramFiles'])/'OLO/ToolGateSetup'
                if uninstaller.parent!=expected or not uninstaller.is_file():raise ValueError('Registered client uninstaller is missing')
                run([str(uninstaller),'/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART'])
                query=subprocess.run(['sc.exe','query','OloToolGateClient'],capture_output=True,text=True)
                if query.returncode!=1060:raise ValueError('Uninstall left a service registration')
                if config.exists() or (Path(os.environ['ProgramFiles'])/'OLO/ToolGate/olo-toolgate-client.exe').exists():raise ValueError('Uninstall left the client configuration or service executable')
                if {path.name for path in state.iterdir()}!={'device-key'} or hashlib.sha256((state/'device-key').read_bytes()).hexdigest()!=device_key_digest:raise ValueError('Uninstall did not remove every enrollment while retaining the device key')
                if int(run([powershell,'-NoProfile','-NonInteractive','-Command',tray_query]).stdout.strip())!=0:raise ValueError('Uninstall left the tray running')
                for registry_path, value in [(r'Software\Microsoft\Windows\CurrentVersion\Run','OloToolGateTray'),(r'Software\Google\Chrome\NativeMessagingHosts\io.ololabs.toolgate.connect',None)]:
                    try:
                        with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE,registry_path,0,winreg.KEY_READ|winreg.KEY_WOW64_64KEY) as entry:
                            winreg.QueryValueEx(entry,value)
                    except FileNotFoundError:continue
                    raise ValueError('Uninstall left tray startup or Chrome bridge registration')
            else:run(['sudo','-n',str(binary),'uninstall','--purge'])


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--target',required=True)
    check(parser.parse_args().target)
