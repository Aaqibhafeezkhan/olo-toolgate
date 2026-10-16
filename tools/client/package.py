# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Deterministic native release skeleton; consumes an already tested/signed binary."""
import argparse
import hashlib
import io
import json
import subprocess
import os
import tarfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TARGETS = {
    'x86_64-unknown-linux-gnu', 'aarch64-unknown-linux-gnu',
    'x86_64-pc-windows-msvc', 'aarch64-pc-windows-msvc',
    'x86_64-apple-darwin', 'aarch64-apple-darwin',
}
CROSS_TARGETS = {'x86_64-pc-windows-gnu'}

def verify_binary(raw,target):
    """Check complete executable header and CPU architecture, not just an archive suffix."""
    architecture='aarch64' if target.startswith('aarch64') else 'x86_64'
    if len(raw)<64:raise ValueError('Incomplete executable header')
    if 'windows' in target:
        offset=int.from_bytes(raw[60:64],'little');machine=int.from_bytes(raw[offset+4:offset+6],'little')
        valid=raw[:2]==b'MZ' and raw[offset:offset+4]==b'PE\0\0' and machine==({'x86_64':0x8664,'aarch64':0xaa64}[architecture])
    elif 'linux' in target:
        valid=raw[:6]==b'\x7fELF\x02\x01' and int.from_bytes(raw[18:20],'little')==({'x86_64':62,'aarch64':183}[architecture])
    else:
        valid=raw[:4]==b'\xcf\xfa\xed\xfe' and int.from_bytes(raw[4:8],'little')==({'x86_64':0x1000007,'aarch64':0x100000c}[architecture])
    if not valid:raise ValueError('Native executable architecture does not match target')

def package(binary, target, output):
    if target not in TARGETS | CROSS_TARGETS or not binary.is_file(): raise ValueError('Tested native binary and supported target required')
    version = (ROOT/'VERSION').read_text().strip()
    command=['cargo','metadata','--locked','--format-version','1']
    if os.environ.get('TOOLGATE_DOCKER_TOOLS')=='1':
        command=['docker','run','--rm','-v',f'{ROOT.as_posix()}:/work','-v',f'{(ROOT/".dev/cargo-registry").as_posix()}:/usr/local/cargo/registry','-w','/work','rust:1.94-bookworm',*command]
    metadata = json.loads(subprocess.check_output(command, cwd=ROOT))
    nodes = {n['id']: n for n in metadata['resolve']['nodes']}
    root = next(p['id'] for p in metadata['packages'] if p['name'] == 'olo-toolgate-client')
    extra_packages=[]
    for executable_name,package_name in (('toolgate','toolgate-cli'),('olo-toolgate-tool-host','olo-toolgate-tool-host')):
        if binary.with_name(executable_name+('.exe' if 'windows' in target else '')).is_file():
            extra_packages.append(next(p['id'] for p in metadata['packages'] if p['name']==package_name))
    reachable, pending = set(), [root,*extra_packages]
    while pending:
        node = pending.pop()
        if node not in reachable:
            reachable.add(node); pending.extend(nodes[node]['dependencies'])
    sbom = {'bomFormat':'CycloneDX','specVersion':'1.6','version':1,'components':[
        {'type':'library','name':p['name'],'version':p['version'],
         'licenses':[{'expression':p['license'].replace('/', ' OR ')}]}
        for p in sorted(metadata['packages'], key=lambda p:p['id']) if p['id'] in reachable
    ]}
    executable = 'olo-toolgate-client.exe' if 'windows' in target else 'olo-toolgate-client'
    raw=binary.read_bytes()
    verify_binary(raw,target)
    files = {executable:binary.read_bytes(), 'LICENSE':(ROOT/'LICENSE').read_bytes(),
             'RELEASE-NOTES.md':(ROOT/'RELEASE-NOTES.md').read_bytes(),
             'NOTICE.md':(ROOT/'NOTICE.md').read_bytes(),
             'README.md':(ROOT/'apps/endpoint-client/README.md').read_text().replace('../../docs/','docs/').encode(),
             'sbom.cdx.json':(json.dumps(sbom,sort_keys=True,indent=2)+'\n').encode()}
    if (ROOT/'release.json').is_file():
        release=json.loads((ROOT/'release.json').read_text())
        if release['version']!=version:raise ValueError('Native release identity differs from product version')
        files['release.json']=(ROOT/'release.json').read_bytes()
    # Native distributions also carry the authoring CLI and server Tool Host when built.
    for name in ('toolgate','olo-toolgate-tool-host'):
        name += '.exe' if 'windows' in target else ''
        extra=binary.with_name(name)
        if extra.is_file():
            verify_binary(extra.read_bytes(),target)
            files[name]=extra.read_bytes()
    if 'windows' in target:
        files['packaging/olo.png']=(ROOT/'apps/admin-ui/src/assets/olo.png').read_bytes()
        host=binary.with_name('olo-toolgate-browser-host.exe')
        host_bytes=host.read_bytes();verify_binary(host_bytes,target)
        files[host.name]=host_bytes
    # Ship maintained operator guides; retired runtime stubs and internal module
    # completion reports were consolidated into the runtime guide and planning index.
    for document in ['client/connect.md','client/installers.md','client/server-mcp.md','adr/012-client-mcp-polling-relay.md',
                     'client/tool-builder.md','adr/011-designated-client-tool-authoring.md','client/package-deployment.md',
                     'adr/010-signed-fleet-reconciliation.md','client/hotfolder.md','client/local-runtimes.md',
                     'adr/009-managed-local-runtime-sandbox.md','adr/007-endpoint-enrollment.md','adr/008-hotfolder-builtins.md',
                     'operations/execution-guide.md','operations/debugging.md','planning/README.md']:
        files['docs/'+document]=(ROOT/'docs'/document).read_bytes()
    files['packaging/runtime-seccomp.json']=(ROOT/'apps/endpoint-client/packaging/runtime-seccomp.json').read_bytes()
    for schema in sorted((ROOT/'packages/contracts/schemas/v1').glob('*.json')):
        files['packages/contracts/schemas/v1/'+schema.name]=schema.read_bytes()
    files['packages/contracts/openapi/control-v1.yaml']=(ROOT/'packages/contracts/openapi/control-v1.yaml').read_bytes()
    files['packages/contracts/VERSION']=(ROOT/'packages/contracts/VERSION').read_bytes()
    for example in (ROOT/'examples/local-runtime-python').iterdir():
        if example.is_file(): files['examples/local-runtime-python/'+example.name]=example.read_bytes()
    for example in (ROOT/'examples/tool-builder-python').iterdir():
        if example.is_file(): files['examples/tool-builder-python/'+example.name]=example.read_bytes()
    for tool in ['tools/builder/sign.py','tools/requirements.txt']:
        files[tool]=(ROOT/tool).read_bytes()
    signature=binary.with_name(binary.name+'.sig')
    if signature.exists():
        if signature.is_symlink() or not signature.is_file() or signature.stat().st_size>4096:raise ValueError('Invalid external signature')
        files[executable+'.sig']=signature.read_bytes()
    for dependency in sorted(metadata['packages'],key=lambda p:p['id']):
        if dependency['id'] not in reachable or dependency['source'] is None:continue
        manifest_directory=dependency['manifest_path'].rsplit('/',1)[0] if os.environ.get('TOOLGATE_DOCKER_TOOLS')=='1' else str(Path(dependency['manifest_path']).parent)
        directory=Path(manifest_directory)
        if os.environ.get('TOOLGATE_DOCKER_TOOLS')=='1':
            directory=ROOT/'.dev/cargo-registry'/manifest_directory.removeprefix('/usr/local/cargo/registry/')
        notices=[p for p in directory.iterdir() if p.is_file() and p.name.upper().startswith(('LICENSE','NOTICE','COPYRIGHT','AUTHORS'))]
        if not notices:
            upstream=ROOT/'tools/client/third-party-notices'/f"{dependency['name']}-{dependency['version']}"
            if upstream.is_dir():notices=[p for p in upstream.iterdir() if p.is_file()]
        if not notices:raise ValueError('Dependency notices missing: '+dependency['name'])
        for notice in notices:
            if notice.stat().st_size>1048576:raise ValueError('Dependency notice exceeds size limit')
            files[f"third-party/{dependency['name']}-{dependency['version']}/{notice.name}"]=notice.read_bytes()
    for path in sorted((ROOT/'apps/endpoint-client/packaging').iterdir()): files['packaging/'+path.name] = path.read_bytes()
    output.mkdir(parents=True, exist_ok=True)
    archive = output/f'olo-toolgate-client-{version}-{target}.{"zip" if "windows" in target else "tar.gz"}'
    if 'windows' in target:
        with zipfile.ZipFile(archive,'w',compression=zipfile.ZIP_DEFLATED) as writer:
            for name,data in sorted(files.items()):
                info = zipfile.ZipInfo(name, (1980,1,1,0,0,0)); info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = (0o755 if name in (executable,'toolgate.exe','olo-toolgate-tool-host.exe') else 0o644) << 16
                writer.writestr(info,data)
    else:
        import gzip
        with archive.open('wb') as stream, gzip.GzipFile(fileobj=stream,mode='wb',filename='',mtime=0) as compressed, tarfile.open(fileobj=compressed,mode='w') as writer:
            for name,data in sorted(files.items()):
                info = tarfile.TarInfo(name); info.size = len(data); info.mtime = 0; info.mode = 0o755 if name in (executable,'toolgate','olo-toolgate-tool-host') else 0o644
                writer.addfile(info,io.BytesIO(data))
    (output/(archive.name+'.sha256')).write_text(hashlib.sha256(archive.read_bytes()).hexdigest()+'  '+archive.name+'\n')
    print(archive)

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary',type=Path,required=True); parser.add_argument('--target',required=True)
    parser.add_argument('--output',type=Path,default=ROOT/'build/client/release')
    args = parser.parse_args(); package(args.binary,args.target,args.output)
