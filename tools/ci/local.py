# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Inventory CI triggers and replay job bodies from the workflow YAML locally.

The YAML owns commands, actions, versions and job dependencies. This module
only owns local execution policy; it never enables or publishes GitHub CI.
"""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import tomllib

try:
    import yaml
except ImportError:
    raise SystemExit('Install validation dependencies: python -m pip install -r tools/requirements.txt')

ROOT = Path(__file__).resolve().parents[2]
IMAGE = 'olo-toolgate-local-ci:runner'

# Coverage policy, NOT a second list of commands. New jobs require a decision.
POLICY = {
    'quality-summary.reports': 'hosted-report',
    'create-release-branch.prepare': 'publication',
    'build-release.select': 'publication',
    'build-release.collect': 'artifacts',
    'build-release.publish': 'publication',
    'admission.admit': 'admission',
    # A read-only PR gate requires hosted base/head event SHAs; its policy is
    # covered offline by test_pr_policy, not replayed as a build/publication job.
    'pr-policy.contribution-policy': 'admission',
    'preflight.metadata': 'replay',
    'foundation.python': 'replay',
    'foundation.ui': 'replay',
    'foundation.rust': 'replay',
    'foundation.examples': 'replay',
    'foundation.scans': 'replay',
    'foundation.browser': 'staged',
    'foundation.java': 'staged',
    'foundation.core-mcp': 'staged',
    'foundation.check': 'staged',
    'foundation.python-sdk': 'python-matrix',
    'images.quickstart': 'artifacts',
    'images.control': 'artifacts',
    'images.gateway': 'artifacts',
    'images.device': 'artifacts',
    'images.secured-device': 'artifacts',
    'images.langchain': 'artifacts',
    'images.build': 'artifacts',
    'images.verify': 'artifacts',
    'gateway.container-and-replicas': 'legacy',
    'policy.enterprise-access': 'legacy',
    'client.native': 'native',
    'client.public-bundle': 'artifacts',
    'tenant-qualification.evidence': 'artifacts',
    'control.container-and-replicas': 'artifacts',
    'quickstart.image': 'artifacts',
    'release-images.publish': 'publication',
    'release-maven.publish': 'publication',
    'release-typescript.publish': 'publication',
    'development-release.prepare': 'publication',
    'development-release.publish': 'publication',
    'development-release.publish-marketplace': 'publication',
    'manual-development-release.native': 'native',
    'manual-development-release.select': 'publication',
    'manual-development-release.prepare': 'artifacts',
    'manual-development-release.build': 'publication',
    'manual-development-release.publish': 'publication',
    'manual-development-release.publish-typescript': 'publication',
    'manual-development-release.publish-marketplace': 'publication',
}
REASONS = {
    'hosted-report': 'Reporting-only coverage collection and Sonar upload use hosted repository variables, optional secrets and Actions summaries; collector helpers can run independently locally.',
    'python-matrix': 'Requires the hosted Python 3.10â€“3.14 and Linux, Windows and macOS matrix; foundation also runs the SDK tests locally on Python 3.12.',
    'legacy': 'Compatibility-only reusable body; the unified main pipeline owns these checks in foundation and images, so local default replay does not repeat them.',
    'admission': 'Shared admission logic is tested offline by tests/ci; GitHub event routing and repository variables require hosted validation.',
    'native': 'Requires all six native VMs and system service/installer support.',
    'artifacts': 'Requires genuine artifacts from the complete six-platform native matrix.',
    'staged': 'Consumes artifacts uploaded by an earlier job of the same run; replay the producing jobs locally and rely on hosted CI for the hand-off.',
    'publication': 'Requires protected environments, credentials or GitHub release/registry writes.',
}
ALLOWED_ACTIONS = {'actions/checkout', 'actions/setup-python', 'actions/setup-java',
                   'actions/setup-node', 'actions/upload-artifact'}
DOCKER_SETUP = {
    'name': 'Prepare isolated local Docker engine',
    'run': """mkdir -p /var/run/local-ci
# Delegate controllers inside this runner's private cgroup namespace before nesting Docker.
# A populated cgroup v2 root otherwise produces invalid domain groups for PostgreSQL.
if [ -f /sys/fs/cgroup/cgroup.controllers ]; then
  mkdir -p /sys/fs/cgroup/init
  for attempt in $(seq 1 10); do
    for process in $(cat /sys/fs/cgroup/cgroup.procs); do
      echo "$process" > /sys/fs/cgroup/init/cgroup.procs 2>/dev/null || true
    done
    controllers=$(sed 's/[^ ]*/+&/g' /sys/fs/cgroup/cgroup.controllers)
    if echo "$controllers" > /sys/fs/cgroup/cgroup.subtree_control; then break; fi
    if [ "$attempt" = 10 ]; then exit 1; fi
  done
fi
dockerd --host=unix:///var/run/docker.sock > /var/run/local-ci/docker.log 2>&1 &
for attempt in $(seq 1 60); do
  if docker info >/dev/null 2>&1; then exit 0; fi
  sleep 1
done
cat /var/run/local-ci/docker.log
exit 1
""",
}
WORKSPACE_SETUP = {
    'name': 'Materialize the reviewed Git checkout for Linux',
    'run': """python3 - <<'PY'
from pathlib import Path
import shutil
import subprocess
import tempfile

# act copies Windows files rather than performing a Linux checkout. The snapshot
# index contains every reviewed change and its canonical line endings and modes.
with tempfile.TemporaryDirectory(prefix='toolgate-local-checkout-') as temporary:
    exported = Path(temporary) / 'tree'
    exported.mkdir()
    subprocess.run(['git', 'checkout-index', '--all', '--prefix=' + str(exported) + '/'], check=True)
    shutil.copytree(exported, Path.cwd(), dirs_exist_ok=True, symlinks=True)
PY
""",
}


def load_workflows(root=ROOT):
    workflows = {}
    paths = sorted((root / '.github/workflows').glob('*.y*ml'))
    for path in paths:
        if path.stem in workflows:
            raise ValueError(f'Duplicate workflow name: {path.stem}')
        document = yaml.safe_load(path.read_text(encoding='utf-8'))
        if not isinstance(document, dict) or not document.get('jobs'):
            raise ValueError(f'Invalid workflow: {path.name}')
        # PyYAML uses YAML 1.1 and interprets an unquoted "on" as True.
        events = document.pop(True, document.get('on', {}))
        if isinstance(events, str):
            events = {events: None}
        elif isinstance(events, list):
            events = dict.fromkeys(events)
        if not isinstance(events, dict):
            raise ValueError(f'Invalid triggers: {path.name}')
        document['on'] = events
        workflows[path.stem] = document
    if not workflows:
        raise ValueError('No workflows found')
    return workflows


def needs(job):
    value = job.get('needs', [])
    return [value] if isinstance(value, str) else value


def inventory(workflows):
    """Validate dependency/call graphs and account for every executable job."""
    rows = []
    graph = {}
    for workflow, document in workflows.items():
        for name, job in document['jobs'].items():
            key = f'{workflow}.{name}'
            parents = needs(job)
            if any(parent not in document['jobs'] for parent in parents):
                raise ValueError(f'{key}: unknown dependency')
            graph[key] = [f'{workflow}.{parent}' for parent in parents]
            row = dict(id=key, needs=graph[key], condition=job.get('if'),
                       runner=job.get('runs-on'), matrix=job.get('strategy', {}).get('matrix'))
            if 'uses' in job:
                target = re.fullmatch(r'\./\.github/workflows/([^/]+)\.ya?ml', job['uses'])
                if not target or target[1] not in workflows:
                    raise ValueError(f'{key}: unsupported reusable workflow {job["uses"]}')
                called = workflows[target[1]]
                if 'workflow_call' not in called['on']:
                    raise ValueError(f'{key}: target does not accept workflow_call')
                graph[key] += [f'{target[1]}.{child}' for child in called['jobs']]
                row.update(kind='reusable', calls=target[1])
            else:
                if key not in POLICY:
                    raise ValueError(f'{key}: no local coverage policy; update tools/ci/local.py POLICY')
                kind = POLICY[key]
                row.update(kind=kind, reason=REASONS.get(kind))
                if kind == 'replay':
                    validate_replay(key, document, job)
            rows.append(row)
    visited = set()

    def visit(key, active):
        if key in active:
            raise ValueError(f'Workflow dependency/call cycle: {key}')
        if key not in visited:
            for parent in graph[key]:
                visit(parent, active | {key})
            visited.add(key)

    for key in graph:
        visit(key, set())
    return rows


def replay_body(key, document, job):
    body = copy.deepcopy(job)
    # Hosted release builds read their immutable preparation record. Local job
    # replay derives versions solely from the checkout, without a GitHub token.
    for step in body.get('steps', []):
        if (step.get('name') == 'Derive build versions from version.json'
                and step.get('run') == 'python -m pip install -r tools/release/notes-requirements.txt\npython tools/release/config.py\n'
                and step.get('env') == {'GH_TOKEN': '${{ github.token }}'}):
            del step['env']
    if key.startswith('foundation.'):
        if body.get('env', {}).get('TOOLGATE_BUILD') == "${{ inputs.build_number || 'local' }}":
            body['env']['TOOLGATE_BUILD'] = 'local'
        # A local foundation replay always validates the current checkout.
        for step in body.get('steps', []):
            if step.get('uses', '').startswith('actions/checkout@'):
                if step.get('with', {}).get('ref') == '${{ inputs.source_sha || github.sha }}':
                    del step['with']['ref']
    # A standalone preflight replay includes the browser gate. Main delegates
    # it to foundation; resolve only this reviewed boolean with its safe default.
    for step in body.get('steps', []):
        if (key == 'preflight.metadata' and step.get('if') == '${{ !inputs.shared_checks }}'
                and document['on']['workflow_call']['inputs']['shared_checks'].get('default') is False):
            del step['if']
    return body


def validate_replay(key, document, job):
    job = replay_body(key, document, job)
    if job.get('runs-on') != 'ubuntu-24.04' or 'strategy' in job:
        raise ValueError(f'{key}: local replay supports Ubuntu 24.04 amd64 job bodies only')
    allowed = {'name', 'needs', 'runs-on', 'steps', 'timeout-minutes', 'env', 'defaults'}
    if set(job) - allowed or set(document) & {'env', 'defaults', 'services'}:
        raise ValueError(f'{key}: new workflow/job context needs local coverage review')
    for step in job.get('steps', []):
        # Status-only artifact capture preserves failed scan evidence without
        # changing which validation commands run. Keep the condition in replay.
        capture_always=(step.get('if')=='always()'
                        and step.get('uses','').split('@')[0]=='actions/upload-artifact')
        if 'if' in step and not capture_always:
            raise ValueError(f'{key}: conditional steps need explicit local coverage review')
        if 'uses' in step and step['uses'].split('@')[0] not in ALLOWED_ACTIONS:
            raise ValueError(f'{key}: action needs local coverage review: {step["uses"]}')
    content = json.dumps(job)
    if re.search(r'secrets\.|github\.token|\b(?:docker|helm|git)\s+push\b|\bnpm\s+publish\b|\bgh\s+release\b|PublicationToRemoteRepository', content):
        raise ValueError(f'{key}: publication or credentials cannot be replayed locally')
    # Removing needs is intentional for a job-body replay. Never discard an
    # expression which depended on outputs or an originating GitHub event.
    if re.search(r'\b(?:needs|github|inputs|vars)\.', content):
        raise ValueError(f'{key}: GitHub context needs explicit local coverage review')


def replay_document(key, workflows):
    workflow, name = key.split('.', 1)
    document = workflows[workflow]
    job = document['jobs'][name]
    validate_replay(key, document, job)
    body = replay_body(key, document, job)
    body.pop('needs', None)
    steps = [copy.deepcopy(DOCKER_SETUP)]
    for step in body['steps']:
        steps.append(step)
        if step.get('uses', '').split('@')[0] == 'actions/checkout':
            steps.append(copy.deepcopy(WORKSPACE_SETUP))
    body['steps'] = steps
    return {'name': 'Local ' + key, 'on': {'workflow_dispatch': {}},
            'permissions': {'contents': 'read'}, 'jobs': {'replay': body}}


def fingerprint(root=ROOT):
    """Hash the working tree, including untracked source, not just HEAD."""
    names = subprocess.check_output(['git', 'ls-files', '--cached', '--others',
                                     '--exclude-standard', '-z'], cwd=root).split(b'\0')
    digest = hashlib.sha256()
    for name in sorted(set(names) - {b''}):
        path = root / name.decode('utf-8')
        digest.update(name + b'\0')
        digest.update(hashlib.sha256(path.read_bytes()).digest() if path.is_file() else b'deleted')
    return digest.hexdigest()


def create_snapshot(destination, root=ROOT):
    """Export current source into a normal Git repo usable inside containers.

    Ignored credentials/build outputs and the original Git metadata stay out.
    Stage working tree changes too, so index-based CI scans test this source.
    """
    destination.mkdir()
    names = subprocess.check_output(['git', 'ls-files', '--cached', '--others',
                                     '--exclude-standard', '-z'], cwd=root).split(b'\0')
    for name in sorted(set(names) - {b''}):
        source = root / name.decode('utf-8')
        target = destination / name.decode('utf-8')
        if source.is_symlink():
            target.parent.mkdir(parents=True, exist_ok=True)
            target.symlink_to(os.readlink(source))
        elif source.is_file():
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
    execute(['git', 'init', '--quiet', '--template=', '--initial-branch=local-ci'], destination)
    execute(['git', 'config', 'core.autocrlf', 'false'], destination)
    execute(['git', 'add', '--force', '.'], destination)
    # Windows cannot preserve Unix executable bits with copy2. Recover them
    # from the source index so gradlew/shell scripts behave as on GitHub.
    entries = subprocess.check_output(['git', 'ls-files', '--stage', '-z'], cwd=root).split(b'\0')
    for entry in entries:
        if entry.startswith(b'100755 '):
            name = entry.split(b'\t', 1)[1].decode('utf-8')
            if (destination / name).is_file():
                execute(['git', 'update-index', '--chmod=+x', '--', name], destination)
    # Disable hooks ONLY in the disposable source snapshot, which is an
    # execution fixture rather than a commit intended for the user's branch.
    execute(['git', '-c', 'core.hooksPath=', '-c', 'user.name=Local CI',
             '-c', 'user.email=local-ci@example.invalid', 'commit', '--quiet', '-m',
             'RC: Local CI working tree snapshot'], destination)
    return destination


def execute(command, root=ROOT, log=None, env=None):
    print('+ ' + subprocess.list2cmdline([str(part) for part in command]), flush=True)
    result = subprocess.run(command, cwd=root, text=True, stdout=log,
                            stderr=subprocess.STDOUT if log else None, env=env)
    if result.returncode:
        raise RuntimeError(f'Command failed ({result.returncode}); ' + (f'see {log.name}' if log else str(command[0])))


def audit(root=ROOT):
    output = root / 'build/local-ci'
    output.mkdir(parents=True, exist_ok=True)
    report = dict(scope='configuration/admission/metadata audit', passed=False,
                  sourceSha256=fingerprint(root), allGitHubTriggersVerified=False)
    try:
        # These are existing shared CI gates, not copies of their implementation.
        execute([sys.executable, 'tools/ci/preflight.py'], root)
        execute([sys.executable, '-m', 'unittest', 'discover', '-s', 'tests/ci', '-v'], root)
        execute(['docker', 'run', '--rm', '-v', f'{root.as_posix()}:/work:ro', '-w',
                 '/work', 'rhysd/actionlint:1.7.7', *[
                     path.relative_to(root).as_posix() for path in sorted(
                         (root / '.github/workflows').glob('*.y*ml'))]], root)
        report['finalSourceSha256'] = fingerprint(root)
        if report['sourceSha256'] != report['finalSourceSha256']:
            raise RuntimeError('Source changed during audit; rerun against the final working tree')
        report['passed'] = True
    except (RuntimeError, OSError) as error:
        report['error'] = str(error)
        raise
    finally:
        (output / 'audit-report.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')


def act_environment():
    # Do not inherit release credentials from the calling shell.
    names = {'PATH', 'PATHEXT', 'SYSTEMROOT', 'WINDIR', 'TEMP', 'TMP', 'HOME',
             'USERPROFILE', 'APPDATA', 'LOCALAPPDATA', 'XDG_CONFIG_HOME',
             'DOCKER_HOST', 'DOCKER_CONTEXT', 'DOCKER_CERT_PATH', 'DOCKER_TLS_VERIFY'}
    return {key: value for key, value in os.environ.items() if key.upper() in names}


def check_act_config(root=ROOT):
    # act has no flag to ignore global/local .actrc files. Refuse potentially
    # injected credentials, alternate workflows or self-hosted runner mappings.
    config = Path(os.environ.get('XDG_CONFIG_HOME', Path.home() / '.config'))
    paths = {root / '.actrc', Path.home() / '.actrc', config / 'act/actrc'}
    found = [str(path) for path in paths if path.is_file()]
    if found:
        raise ValueError('Local replay requires no implicit act configuration: ' + ', '.join(sorted(found)))


def act_command(executable, workflow, workspace, root=ROOT, image=IMAGE, network='toolgate-local-ci'):
    empty = workspace / 'empty.env'
    empty.write_text('', encoding='utf-8')
    return [executable, 'workflow_dispatch', '--directory', str(root),
            '--workflows', str(workflow), '--job', 'replay',
            '--platform', 'ubuntu-24.04=' + image,
            '--container-architecture', 'linux/amd64', '--pull=false', '--rm',
            '--container-options', '--privileged --cgroupns=private --volume /var/lib/docker',
            '--network', network, '--container-daemon-socket', '-',
            '--artifact-server-path', str(workspace / 'artifacts'),
            '--secret-file', str(empty), '--var-file', str(empty),
            '--env-file', str(empty), '--input-file', str(empty)]


def build_runner(root=ROOT):
    toolchain = tomllib.loads((root / 'rust-toolchain.toml').read_text(encoding='utf-8'))
    version = toolchain['toolchain']['channel']
    execute(['docker', 'build', '--build-arg', f'RUST_VERSION={version}', '-t', IMAGE,
             '-f', 'tools/ci/local.Dockerfile', '.'], root)


def replayable_jobs(rows):
    """Select supported bodies from the validated inventory, including new ones."""
    selected = [row['id'] for row in rows if row['kind'] == 'replay']
    if not selected:
        raise ValueError('No replayable jobs found; local coverage needs review')
    return selected


def run_jobs(workflows, rows, selected, output, executable='act', image=IMAGE, root=ROOT):
    baseline = fingerprint(root)
    leaves = [row for row in rows if row['kind'] != 'reusable']
    wanted = set(selected or [row['id'] for row in leaves])
    unknown = wanted - {row['id'] for row in leaves}
    if unknown:
        raise ValueError('Unknown executable job: ' + ', '.join(sorted(unknown)))
    output.mkdir(parents=True, exist_ok=True)
    report = dict(scope='selected job bodies' if selected else 'all job bodies',
                  sourceSha256=baseline, allGitHubTriggersVerified=False,
                  passed=False, jobs=[])
    try:
        replay = [row for row in leaves if row['id'] in wanted and row['kind'] == 'replay']
        if replay:
            check_act_config(root)
            if not shutil.which(executable):
                raise ValueError('act is required for job replay: https://nektosact.com/installation/')
            execute(['docker', 'info', '--format', '{{.OSType}}'], root)
            if image == IMAGE:
                build_runner(root)
        with tempfile.TemporaryDirectory(prefix='replay-', dir=output) as temp:
            workspace = Path(temp)
            snapshot = create_snapshot(workspace / 'source', root) if replay else root
            network = 'toolgate-local-ci-' + workspace.name
            if replay:
                execute(['docker', 'network', 'create', network], root)
            try:
                for row in leaves:
                    key = row['id']
                    if key not in wanted:
                        report['jobs'].append(dict(id=key, status='not-selected'))
                        continue
                    if row['kind'] != 'replay':
                        report['jobs'].append(dict(id=key, status='unverified', reason=row['reason']))
                        print(f'UNVERIFIED {key}: {row["reason"]}', flush=True)
                        continue
                    workflow = workspace / (key + '.yml')
                    workflow.write_text(yaml.safe_dump(replay_document(key, workflows), sort_keys=False), encoding='utf-8')
                    logfile = output / (key + '.log')
                    print(f'Running {key}; log: {logfile}', flush=True)
                    try:
                        with logfile.open('w', encoding='utf-8') as log:
                            execute(act_command(str(Path(shutil.which(executable)).resolve()), workflow,
                                                workspace, snapshot, image, network), root,
                                    log=log, env=act_environment())
                    except (RuntimeError, OSError) as error:
                        report['jobs'].append(dict(id=key, status='failed', log=str(logfile), reason=str(error)))
                        raise
                    report['jobs'].append(dict(id=key, status='passed', log=str(logfile)))
                    print(f'PASSED {key}', flush=True)
            finally:
                if replay:
                    execute(['docker', 'network', 'rm', network], root)
        report['finalSourceSha256'] = fingerprint(root)
        if report['finalSourceSha256'] != baseline:
            raise RuntimeError('Source changed during replay; rerun against the final working tree')
        report['passed'] = all(row['status'] == 'passed' for row in report['jobs'] if row['id'] in wanted)
        return 0 if report['passed'] else 2
    except (ValueError, RuntimeError, OSError) as error:
        report['error'] = str(error)
        raise
    finally:
        (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
        print('Report: ' + str(output / 'report.json'), flush=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['plan', 'audit', 'run'], nargs='?', default='plan')
    selection = parser.add_mutually_exclusive_group()
    selection.add_argument('--job', action='append', help='Replay a WORKFLOW.JOB; repeat to select several')
    selection.add_argument('--replayable', action='store_true',
                           help='Replay every locally supported job body (used by RC: commits)')
    parser.add_argument('--act', default='act', help='Path to an installed act executable')
    parser.add_argument('--image', default=IMAGE, help='Custom Ubuntu runner image; default is built locally')
    args = parser.parse_args(argv)
    if args.command != 'run' and (args.job or args.replayable):
        parser.error('--job and --replayable require run')
    output = ROOT / 'build/local-ci'
    try:
        workflows = load_workflows()
        rows = inventory(workflows)
        if args.command == 'plan':
            plan = dict(triggers={name: document['on'] for name, document in workflows.items()}, jobs=rows)
            output.mkdir(parents=True, exist_ok=True)
            (output / 'plan.json').write_text(json.dumps(plan, indent=2) + '\n', encoding='utf-8')
            for name, document in workflows.items():
                print(f'{name}: ' + json.dumps(document['on']))
            for row in rows:
                print(f'{row["id"]}: {row["kind"]}')
            print('Plan: ' + str(output / 'plan.json'))
            return 0
        if args.command == 'run':
            output.mkdir(parents=True, exist_ok=True)
            # Replace previous success before the audit, including failures
            # which prevent replay from starting.
            (output / 'report.json').write_text(json.dumps(dict(
                scope='pending audit', passed=False, allGitHubTriggersVerified=False,
                sourceSha256=fingerprint(), jobs=[]), indent=2) + '\n', encoding='utf-8')
        audit()
        if args.command == 'audit':
            print('CI configuration/admission/metadata audit passed; job bodies have not been replayed.')
            return 0
        selected = replayable_jobs(rows) if args.replayable else args.job
        result = run_jobs(workflows, rows, selected, output, args.act, args.image)
        print('Selected job bodies passed.' if result == 0 else 'Local coverage is incomplete; see report.json.')
        print('Hosted native jobs, event routing and publication still require GitHub validation.')
        return result
    except (ValueError, RuntimeError, OSError) as error:
        print(str(error), file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
