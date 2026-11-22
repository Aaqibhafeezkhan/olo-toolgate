# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Published-image OLO installation; shares provisioning with the local developer stack."""
import argparse
from datetime import datetime
import time
import os
from pathlib import Path
import shutil
import subprocess
import sys

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[3]
SOURCE=ROOT/'debug/profiled'


def settings():
    def read(path):
        values={}
        for line in path.read_text(encoding='utf-8-sig').splitlines():
            line=line.strip()
            if not line or line.startswith('#'):continue
            if '=' not in line:raise ValueError('Use KEY=value in .env')
            key,value=line.split('=',1)
            if key in values:raise ValueError('Duplicate installation setting')
            values[key]=value.strip().strip('"').strip("'")
        return values
    defaults=read(HERE/'.env.example')
    if (HERE/'.env').exists():
        selected=read(HERE/'.env')
        if selected.keys()-defaults.keys():raise ValueError('Unsupported installation setting; use installation.env for runtime settings')
        defaults.update(selected)
    return defaults


def configure(values):
    os.environ.update(OLO_STACK_HOME=str(HERE),OLO_STACK_PROJECT='olo-quickstart-dev',OLO_STACK_IMAGE_MODE='published',
        OLO_STACK_QUICKSTART_IMAGE=values['QUICKSTART_IMAGE'],OLO_STACK_RUNTIME_IMAGE=values['LANGGRAPH_IMAGE'],
        OLO_STACK_DEVICE_IMAGE=values['DEVICE_IMAGE'],OLO_STACK_HTTP_PORT=values['HTTP_PORT'],
        OLO_STACK_TLS_PORT=values['TLS_PORT'],OLO_STACK_REGISTRY_PORT=values['REGISTRY_PORT'])
    sys.path.insert(0,str(ROOT));sys.path.insert(0,str(SOURCE))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation',choices=['start','stop','restart','validate','provision-runtime','ollama-vault'])
    model=parser.add_mutually_exclusive_group();model.add_argument('--with-ollama',action='store_true');model.add_argument('--without-ollama',action='store_true')
    monitor=parser.add_mutually_exclusive_group();monitor.add_argument('--with-monitoring',action='store_true');monitor.add_argument('--without-monitoring',action='store_true')
    parser.add_argument('--bots',help='0 for no bots (default), comma-separated menu numbers, or M for MCP setup instructions')
    parser.add_argument('--bundles-dir',type=Path,help='Directory containing one release inventory and its verified .olo bundles')
    args=parser.parse_args();values=settings();configure(values)
    selected=[];credential_updates={}
    if args.operation=='start':
        from bot.quickstart import choose
        selected=choose(args.bots)
        if selected and not args.without_ollama and any(row['requiresOllama'] for row in selected):args.with_ollama=True
    if args.operation=='start' and sys.stdin.isatty():
        def choose(title):
            while True:
                answer=input(title+' [y/N]: ').strip().lower()
                if answer in ('','y','yes','n','no'):return answer in ('y','yes')
        if not args.with_ollama and not args.without_ollama:args.with_ollama=choose('Install/reuse Ollama with both local models and all NVIDIA GPUs?')
        if not args.with_monitoring and not args.without_monitoring:
            args.with_monitoring=choose('Create/reuse the observability collector?')
            args.without_monitoring=not args.with_monitoring
    def helper(file,operation):subprocess.run([sys.executable,str(SOURCE/file),operation],cwd=ROOT,check=True)
    if args.operation=='ollama-vault':helper('ollama.py','vault');return
    # Prepare monitoring defaults before collecting provider credentials.
    if args.operation=='start' and args.with_monitoring:helper('observability/manage.py','configure')
    import stack
    if args.operation=='start' and (selected or args.with_monitoring):
        from bot.quickstart import prompt_credentials
        credential_updates,cloud_monitoring=prompt_credentials(stack,interactive=sys.stdin.isatty())
        if cloud_monitoring and not args.without_monitoring:args.with_monitoring=True
    started=time.perf_counter() if args.operation=='start' else None
    if started is not None:
        print('Startup started (inputs finalized): '+datetime.now().astimezone().isoformat(timespec='seconds'),flush=True)
    try:
        if args.operation=='start':
            if not (HERE/'.env').exists():shutil.copyfile(HERE/'.env.example',HERE/'.env')
            if args.with_ollama:helper('ollama.py','configure')
        if args.operation=='start' and args.with_monitoring:helper('observability/manage.py','configure')
        if args.operation=='start':
            selected_images=stack.images()
            probes=[('quickstart',selected_images['quickstart'],'/opt/quickstart-python/bin/python',
                     '/opt/quickstart/workflow_installation.py',
                     "from pathlib import Path; assert Path('/opt/quickstart/workflow_installation.py').is_file()"),
                    ('device',selected_images['device'],'python3','/opt/toolgate/installation_device.py',
                     "from pathlib import Path; assert Path('/opt/toolgate/installation_device.py').is_file()"),
                    ('runtime',selected_images['runtime'],'/opt/toolgate-runtime/bin/python','toolgate_runtime.service',
                     'import toolgate_runtime.service')]
            for role,image,python,required,probe in probes:
                result=subprocess.run(['docker','run','--rm','--network','none','--read-only','--cap-drop','ALL',
                    '--security-opt','no-new-privileges:true','--entrypoint',python,image,'-c',probe],capture_output=True,text=True)
                if result.returncode:
                    raise ValueError(f'Cannot validate published {role} image {image}: requires {required}. '
                        'Check Docker access and select matching WorkflowStack-capable image versions in .env. '
                        'Mutable dev tags were refreshed; an incompatible publication cannot be used.')
        # No application build path is used: published mode only reuses/pulls explicit images.
        sys.argv=[str(SOURCE/'stack.py'),args.operation];stack.main()
        if args.operation in ('start','stop'):
            operation=args.operation
            if args.with_ollama:helper('ollama.py',operation)
            if args.with_monitoring:helper('observability/manage.py',operation)
        if args.operation=='start' and selected:
            from bot.quickstart import configure_selected
            configure_selected(stack,selected,bundles_dir=args.bundles_dir,credential_updates=credential_updates)
            print('All selected bots are ready to run. Open Bots in the console to try them.',flush=True)
    finally:
        if started is not None:
            elapsed=time.perf_counter()-started
            print('Startup ended: '+datetime.now().astimezone().isoformat(timespec='seconds'),flush=True)
            print(f'Total time after user input: {elapsed:.3f} seconds',flush=True)



if __name__=='__main__':
    try:main()
    except (OSError,ValueError,subprocess.CalledProcessError) as failure:
        print('OLO deployment stopped; existing storage and credentials are preserved. '+str(failure),file=sys.stderr)
        raise SystemExit(1)
