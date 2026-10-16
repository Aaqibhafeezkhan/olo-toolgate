# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Build unsigned interactive installers from an exact checksummed native archive."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import zipfile

ROOT=Path(__file__).resolve().parents[2]
EXTENSIONS={'WINDOWS':'setup.exe','MACOS':'dmg','LINUX':'run'}


def embed_chrome(directory_payload, build_number=0):
    if __package__:
        from .extension import package as chrome_package
    else:
        from extension import package as chrome_package
    directory=directory_payload.parent
    metadata=chrome_package(directory/'chrome-build',build_number)
    with zipfile.ZipFile(directory/'chrome-build'/metadata['filename']) as extension_archive:
        extension_archive.extractall(directory_payload/'chrome-extension')


def payload(archive):
    expected=hashlib.sha256(archive.read_bytes()).hexdigest()+'  '+archive.name
    if archive.with_name(archive.name+'.sha256').read_text().strip()!=expected:
        raise ValueError('Native archive checksum mismatch')
    if archive.suffix=='.zip':
        with zipfile.ZipFile(archive) as reader:
            files={info.filename:reader.read(info) for info in reader.infolist() if not info.is_dir()}
    else:
        with tarfile.open(archive) as reader:
            entries=reader.getmembers()
            if any(not entry.isfile() for entry in entries):raise ValueError('Regular payload files required')
            files={entry.name:reader.extractfile(entry).read() for entry in entries}
    for name in files:
        if '\\' in name or ':' in name or name.startswith('/') or '..' in name.split('/'):
            raise ValueError('Unsafe payload path')
    return files


def linux_bytes(files):
    buffer=io.BytesIO()
    import gzip
    with gzip.GzipFile(fileobj=buffer,mode='wb',mtime=0,filename='') as stream, tarfile.open(fileobj=stream,mode='w') as writer:
        for name,data in sorted(files.items()):
            info=tarfile.TarInfo(name);info.size=len(data);info.mode=0o755 if name in ('olo-toolgate-client','toolgate','olo-toolgate-tool-host') else 0o644;info.mtime=0
            writer.addfile(info,io.BytesIO(data))
    data=buffer.getvalue()
    template=(ROOT/'apps/endpoint-client/packaging/linux-setup.sh').read_text(encoding='utf-8').replace('@SHA256@',hashlib.sha256(data).hexdigest())
    offset=0
    while True:
        header=template.replace('@OFFSET@',str(offset)).encode()
        candidate=len(header)+1
        if candidate==offset:break
        offset=candidate
    return header+data


def build(target,output):
    version=(ROOT/'VERSION').read_text().strip()
    release=json.loads((ROOT/'release.json').read_text()) if (ROOT/'release.json').is_file() else None
    if release and release['version'] != version:raise ValueError('Installer release identity differs from product version')
    platform='WINDOWS' if 'windows' in target else 'MACOS' if 'apple' in target else 'LINUX'
    base=f'olo-toolgate-client-{version}-{target}'
    files=payload(output/(base+('.zip' if platform=='WINDOWS' else '.tar.gz')))
    from package import verify_binary, TARGETS, CROSS_TARGETS
    if target not in TARGETS | CROSS_TARGETS:raise ValueError('Supported native target required')
    executable='olo-toolgate-client.exe' if platform=='WINDOWS' else 'olo-toolgate-client'
    verify_binary(files[executable],target)
    if platform=='WINDOWS':
        if 'olo-toolgate-browser-host.exe' not in files:raise ValueError('Combined Windows installer requires the native Chrome host')
        verify_binary(files['olo-toolgate-browser-host.exe'],target)
    result=output/(base+'.'+EXTENSIONS[platform])
    if platform=='LINUX':
        result.write_bytes(linux_bytes(files));result.chmod(0o755)
    else:
        (ROOT/'.dev').mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='installer-',dir=ROOT/'.dev') as temporary:
            directory=Path(temporary);directory_payload=directory/'payload';directory_payload.mkdir()
            for name,data in files.items():
                path=directory_payload/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
                if name in (executable,'toolgate','olo-toolgate-tool-host'):path.chmod(0o755)
            if platform=='WINDOWS':
                # Ship the unpacked extension inside the EXE, never a second ZIP download.
                build_number=release['build'] if release else int(subprocess.check_output(['git','rev-list','--count','HEAD'],cwd=ROOT)) if os.environ.get('GITHUB_ACTIONS')=='true' else 0
                embed_chrome(directory_payload,build_number)
                (directory_payload/'packaging').mkdir(exist_ok=True)
                shutil.copyfile(ROOT/'apps/endpoint-client/packaging/toolgate-tray.ps1',directory_payload/'packaging/toolgate-tray.ps1')
                shutil.copyfile(ROOT/'apps/endpoint-client/packaging/toolgate-uninstall.ps1',directory_payload/'packaging/toolgate-uninstall.ps1')
                shutil.copyfile(ROOT/'apps/endpoint-client/packaging/toolgate-status.ps1',directory_payload/'packaging/toolgate-status.ps1')
                shutil.copyfile(ROOT/'apps/endpoint-client/packaging/toolgate-packets.ps1',directory_payload/'packaging/toolgate-packets.ps1')
                shutil.copyfile(ROOT/'apps/endpoint-client/packaging/toolgate-enroll.ps1',directory_payload/'packaging/toolgate-enroll.ps1')
                shutil.copyfile(ROOT/'apps/endpoint-client/packaging/toolgate-setup-peer.ps1',directory_payload/'packaging/toolgate-setup-peer.ps1')
                compiler=os.environ.get('CLIENT_ISCC_PATH',r'C:\Program Files (x86)\Inno Setup 6\ISCC.exe')
                notice=Path(compiler).parent/'License.txt'
                if not notice.is_file():raise ValueError('Installer compiler license missing')
                destination=directory_payload/'third-party/inno-setup/License.txt'
                destination.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(notice,destination)
                architecture='arm64' if target.startswith('aarch64') else 'x64compatible'
                subprocess.run([compiler,'/Qp','/DProductVersion='+version,
                    *(['/DNumericVersion='+release['numericVersion']] if release else []),'/DNativeArchitecture='+architecture,
                    '/DChromeStoreUrl='+os.environ.get('TOOLGATE_CHROME_STORE_URL',''),
                    '/DPayloadDirectory='+str(directory_payload.resolve()),'/DOutputDirectory='+str(output.resolve()),
                    '/DOutputName='+base+'.setup',str(ROOT/'apps/endpoint-client/packaging/windows-setup.iss')],check=True)
            else:
                app=directory/'Install ToolGate.app'
                subprocess.run(['/usr/bin/osacompile','-o',str(app),str(ROOT/'apps/endpoint-client/packaging/macos-setup.applescript')],check=True)
                if release:
                    import plistlib
                    plist=app/'Contents/Info.plist'
                    properties=plistlib.loads(plist.read_bytes())
                    properties.update(CFBundleShortVersionString=release['baseVersion'],CFBundleVersion=release['macosBuild'])
                    plist.write_bytes(plistlib.dumps(properties))
                shutil.copytree(directory_payload,app/'Contents/Resources/payload')
                shutil.copyfile(ROOT/'docs/client/installers.md',directory/'README.md')
                subprocess.run(['/usr/bin/hdiutil','create','-volname','Install OLO ToolGate','-srcfolder',str(directory),
                    '-format','UDZO','-ov',str(result.resolve())],check=True)
    result.with_name(result.name+'.sha256').write_text(hashlib.sha256(result.read_bytes()).hexdigest()+'  '+result.name+'\n',encoding='utf-8',newline='\n')
    print('Unsigned installer prepared: '+result.name)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--target',required=True);parser.add_argument('--output',type=Path,default=ROOT/'build/client/release')
    arguments=parser.parse_args();build(arguments.target,arguments.output)
