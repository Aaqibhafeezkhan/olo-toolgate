# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Real-engine gate for the client's managed runtime (ADR 026/027): the Hello World package, on the approved JRE base,
runs over tool protocol v2 under the client's sandbox. Owns only its nonce-named image tag and test state."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[2]
# The approved base Control builds client-tool images on (ADR 026); its Java 21.0.12.1 is managed version 21.0.12+1.
BASE = 'eclipse-temurin@sha256:000fd431958bc81a24abe1e8e5f0f0fd3ae365a594bd50aadb20696805f9408c'
PACKAGE = 'ololabs.hello-world'


def run(args, **kwargs):
    return subprocess.run(args, cwd=ROOT, check=True, **kwargs)


def hello_world_jar(cli, work):
    """The jar of the sealed Hello World package, built by the same orchestrator the Control image uses."""
    output = work/'core-mcp'
    run(['python', 'core-mcp/build.py', '--cli', str(cli), '--output', str(output)])
    packages = sorted(output.glob(PACKAGE + '-*.tgpkg'))
    if len(packages) != 1:
        raise ValueError('Expected exactly one Hello World package')
    with zipfile.ZipFile(packages[0]) as archive:
        manifest = json.loads(archive.read('toolgate-package.json'))
        jars = [a['path'] for a in manifest['artifacts'] if a['path'].endswith('.jar')]
        if len(jars) != 1:
            raise ValueError('Hello World must carry exactly one Java artifact')
        (work/'tool.jar').write_bytes(archive.read(jars[0]))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cli', type=Path, required=True, help='The toolgate CLI built for this runner')
    parser.add_argument('--test-binary', type=Path, help='Already compiled execution test executable')
    args = parser.parse_args()
    engine = Path(shutil.which('docker') or '')
    if not engine.is_file():
        raise SystemExit('A local container engine is required for this explicit gate')
    base = Path(os.environ['ProgramData']) if os.name == 'nt' else Path(tempfile.gettempdir())
    state = base/('toolgate-runtime-test-' + uuid.uuid4().hex)
    if os.name != 'nt':
        state.mkdir(mode=0o700)
    endpoint = 'npipe:////./pipe/dockerDesktopLinuxEngine' if os.name == 'nt' else 'unix:///var/run/docker.sock'
    tag = 'olo-toolgate-runtime-test:' + uuid.uuid4().hex
    try:
        (ROOT/'.dev').mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='runtime-image-', dir=ROOT/'.dev') as temporary:
            work = Path(temporary)
            hello_world_jar(args.cli.resolve(strict=True), work)
            # The same layout as Control's client-tool layer: root-owned, read-only, run as 65532 without an entrypoint.
            (work/'Dockerfile').write_text('# Copyright 2026 OLO Labs\n# SPDX-License-Identifier: Apache-2.0\n'
                                           f'FROM {BASE}\nCOPY --chmod=0444 tool.jar /opt/tool/{PACKAGE}/tool.jar\n'
                                           'USER 65532:65532\nENTRYPOINT []\nCMD []\n', encoding='utf-8')
            run(['docker', 'build', '-t', tag, str(work)])
        image = run(['docker', 'image', 'inspect', '--format', '{{.Id}}', tag], capture_output=True, text=True).stdout.strip()
        env = dict(os.environ, TOOLGATE_RUNTIME_TEST_IMAGE=image, TOOLGATE_RUNTIME_TEST_STATE=str(state),
                   TOOLGATE_RUNTIME_TEST_ENGINE=str(engine), TOOLGATE_RUNTIME_TEST_ENDPOINT=endpoint)
        command = [str(args.test_binary.resolve())] if args.test_binary else ['cargo', 'test', '-p', 'olo-toolgate-client', '--test', 'execution', '--locked', '--']
        result = run([*command, 'real_engine_runs_hello_world_over_tool_protocol_v2', '--ignored', '--exact', '--nocapture'],
                     env=env, capture_output=True, text=True)
        print(result.stdout + result.stderr)
        # A filter that matches nothing passes silently; the gate requires the real test to have run.
        if 'test real_engine_runs_hello_world_over_tool_protocol_v2 ... ok' not in result.stdout:
            raise SystemExit('The real-engine Hello World test did not run')
        evidence = ROOT/'build/client/runtime-smoke.json'
        evidence.parent.mkdir(parents=True, exist_ok=True)
        evidence.write_text(json.dumps({'realEngine': True, 'base': BASE, 'package': PACKAGE, 'protocol': 2,
                                        'image': image, 'cleanup': True}, indent=2) + '\n', encoding='utf-8')
        print('Real managed runtime gate passed')
    finally:
        subprocess.run(['docker', 'image', 'rm', tag], cwd=ROOT, capture_output=True)
        if state.resolve().parent != base.resolve() or not state.name.startswith('toolgate-runtime-test-'):
            raise ValueError('Owned test path verification failed')
        if state.exists():
            shutil.rmtree(state)


if __name__ == '__main__':
    main()
