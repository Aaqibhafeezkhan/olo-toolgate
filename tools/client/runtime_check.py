# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Real-engine gate for the client's managed runtime (ADR 026/027): Java and Python Hello World on pinned bases,
runs over tool protocol v2 under the client's sandbox. Owns only its nonce-named image tag and test state."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[2]
# The approved base Control builds client-tool images on (ADR 026); its Java 21.0.12.1 is managed version 21.0.12+1.
BASE = 'eclipse-temurin@sha256:000fd431958bc81a24abe1e8e5f0f0fd3ae365a594bd50aadb20696805f9408c'
PACKAGE = 'ololabs.hello-world'
PYTHON_BASE = 'python@sha256:a6e34c598f2467ed0e9a8d349809fcd8b5c603269512df273a0bb1784edc11b1'


def run(args, **kwargs):
    return subprocess.run(args, cwd=ROOT, check=True, **kwargs)


def hello_world_artifact(output, work, package, extension):
    """Extract the artifact from the same sealed package the Control image ships."""
    packages = sorted(output.glob(package + '-[0-9]*.tgpkg'))
    if len(packages) != 1:
        raise ValueError('Expected exactly one Hello World package')
    with zipfile.ZipFile(packages[0]) as archive:
        manifest = json.loads(archive.read('toolgate-package.json'))
        artifacts = [a['path'] for a in manifest['artifacts'] if a['path'].endswith('.' + extension)]
        if len(artifacts) != 1:
            raise ValueError('Hello World must carry exactly one SDK artifact')
        (work/('tool.' + extension)).write_bytes(archive.read(artifacts[0]))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cli', type=Path, required=True, help='The toolgate CLI built for this runner')
    parser.add_argument('--test-binary', type=Path, help='Already compiled execution test executable')
    parser.add_argument('--sdk', choices=('java', 'python', 'all'), default='all')
    args = parser.parse_args()
    engine = Path(shutil.which('docker') or '')
    if not engine.is_file():
        raise SystemExit('A local container engine is required for this explicit gate')
    base = Path(os.environ['ProgramData']) if os.name == 'nt' else Path(tempfile.gettempdir())
    state = base/('toolgate-runtime-test-' + uuid.uuid4().hex)
    states = []
    endpoint = 'npipe:////./pipe/dockerDesktopLinuxEngine' if os.name == 'nt' else 'unix:///var/run/docker.sock'
    tag = 'olo-toolgate-runtime-test:' + uuid.uuid4().hex
    tags = []
    try:
        (ROOT/'.dev').mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='runtime-image-', dir=ROOT/'.dev') as temporary:
            work = Path(temporary)
            output = work/'core-mcp'
            run([sys.executable, 'core-mcp/build.py', '--cli', str(args.cli.resolve(strict=True)), '--output', str(output)])
            evidence_runs = []
            for sdk in (('java', 'python') if args.sdk == 'all' else (args.sdk,)):
                sdk_tag = tag + '-' + sdk
                tags.append(sdk_tag)
                sdk_state = state.with_name(state.name + '-' + sdk)
                states.append(sdk_state)
                package = PACKAGE + ('-python' if sdk == 'python' else '')
                extension = 'pyz' if sdk == 'python' else 'jar'
                runtime_base = PYTHON_BASE if sdk == 'python' else BASE
                hello_world_artifact(output, work, package, extension)
                # Root-owned, read-only, run as 65532 with the client's fixed launch vector.
                (work/'Dockerfile').write_text('# Copyright 2026 OLO Labs\n# SPDX-License-Identifier: Apache-2.0\n'
                    f'FROM {runtime_base}\nRUN mkdir -p /opt/tool/{package} && chmod 0755 /opt/tool /opt/tool/{package}\n'
                    f'COPY --chmod=0444 tool.{extension} /opt/tool/{package}/tool.{extension}\n'
                    'USER 65532:65532\nENTRYPOINT []\nCMD []\n', encoding='utf-8')
                run(['docker', 'build', '-t', sdk_tag, str(work)])
                image = run(['docker', 'image', 'inspect', '--format', '{{.Id}}', sdk_tag], capture_output=True, text=True).stdout.strip()
                version = '21.0.12+1'
                if sdk == 'python':
                    config = json.loads(run(['docker', 'image', 'inspect', sdk_tag], capture_output=True, text=True).stdout)[0]
                    version = next(item.split('=', 1)[1] for item in config['Config']['Env'] if item.startswith('PYTHON_VERSION='))
                env = dict(os.environ, TOOLGATE_RUNTIME_TEST_IMAGE=image, TOOLGATE_RUNTIME_TEST_STATE=str(sdk_state),
                           TOOLGATE_RUNTIME_TEST_ENGINE=str(engine), TOOLGATE_RUNTIME_TEST_ENDPOINT=endpoint,
                           TOOLGATE_RUNTIME_TEST_SDK=sdk, TOOLGATE_RUNTIME_TEST_VERSION=version)
                command = [str(args.test_binary.resolve())] if args.test_binary else ['cargo', 'test', '-p', 'olo-toolgate-client', '--test', 'execution', '--locked', '--']
                try:
                    result = run([*command, 'real_engine_runs_hello_world_over_tool_protocol_v2', '--ignored', '--exact', '--nocapture'],
                                 env=env, capture_output=True, text=True)
                except subprocess.CalledProcessError as error:
                    print((error.stdout or '') + (error.stderr or ''))
                    raise
                print(result.stdout + result.stderr)
                if 'test real_engine_runs_hello_world_over_tool_protocol_v2 ... ok' not in result.stdout:
                    raise SystemExit('The real-engine Hello World test did not run: ' + sdk)
                evidence_runs.append(dict(sdk=sdk, base=runtime_base, package=package, image=image,
                                          arguments=[{}, {'text': 'Anupriya'}], outputs=['Hello World', 'Hello Anupriya']))
        evidence = ROOT/'build/client/runtime-smoke.json'
        evidence.parent.mkdir(parents=True, exist_ok=True)
        evidence.write_text(json.dumps({'realEngine': True, 'protocol': 2, 'runs': evidence_runs,
                                        'cleanup': True}, indent=2) + '\n', encoding='utf-8')
        print('Real managed runtime gate passed')
    finally:
        for sdk_tag in tags:
            subprocess.run(['docker', 'image', 'rm', sdk_tag], cwd=ROOT, capture_output=True)
        for sdk_state in states:
            if sdk_state.resolve().parent != base.resolve() or not sdk_state.name.startswith('toolgate-runtime-test-'):
                raise ValueError('Owned test path verification failed')
            if sdk_state.exists():
                shutil.rmtree(sdk_state)


if __name__ == '__main__':
    main()
