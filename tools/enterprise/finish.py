# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Run enterprise completion gates sequentially, preserving logs and explicit failures.

Use --only NAME to fix and rerun one gate. A complete invocation is required for
an integrated success report; a partial invocation never claims full acceptance.
Existing debug installations are not used or changed by these isolated fixtures.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
QUICKSTART = 'olo-toolgate-quickstart:enterprise-validation'
GATEWAY = 'olo-toolgate-gateway:enterprise-production'
CONTROL = 'olo-toolgate-control:enterprise-production'


def fingerprint():
    names = subprocess.check_output(['git', 'ls-files', '--cached', '--others',
                                     '--exclude-standard', '-z'], cwd=ROOT).split(b'\0')
    digest = hashlib.sha256()
    for name in sorted(set(names) - {b''}):
        path = ROOT / name.decode('utf-8')
        if path.is_file():
            digest.update(name + b'\0' + hashlib.sha256(path.read_bytes()).digest())
    return digest.hexdigest()


def gates():
    py = sys.executable
    return {
        'quality': [[py, 'tools/quality.py'], [py, 'tools/contracts/generate.py', '--check'],
                    [py, 'tools/ui/generate.py', '--check'],
                    [py, '-m', 'unittest', 'discover', '-s', 'tests/ci', '-v'],
                    [py, '-m', 'unittest', 'discover', '-s', 'tests/contracts', '-v']],
        'java': [[py, 'tools/enterprise/check.py']],
        'native-sdk': [[py, 'tools/enterprise/native.py']],
        'custody': [[py, 'tools/enterprise/custody.py']],
        'sqlite-browser': [[py, 'tools/quickstart/check.py', '--build', '--image', QUICKSTART]],
        'gateway': [[py, 'tools/gateway/container.py', '--image', GATEWAY, '--helper-image', QUICKSTART]],
        'postgresql': [[py, 'tools/quickstart/check.py', '--image', QUICKSTART, '--postgresql', '--no-browser']],
        'control': [[py, 'tools/control/container.py', '--image', CONTROL, '--helper-image', QUICKSTART]],
        'restore': [[py, 'tools/enterprise/restore_check.py']],
        'helm': [[py, 'tools/enterprise/helm.py']],
        'publication': [[py, 'tools/check.py', '--publication-only']],
        'scans': [[py, 'tools/check.py', '--scans']],
        'images': [[py, 'tools/enterprise/images.py', QUICKSTART, GATEWAY, CONTROL]],
        'acceptance': [[py, 'tools/enterprise/acceptance.py', '--require-complete']],
    }


def main():
    commands = gates()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--only', choices=commands)
    parser.add_argument('--resume', action='store_true', help='Reuse passing gate reports only for the exact current source fingerprint')
    args = parser.parse_args()
    evidence = ROOT / 'build/enterprise/finish'
    evidence.mkdir(parents=True, exist_ok=True)
    baseline = fingerprint()
    report = dict(sourceSha256=baseline, revision=subprocess.check_output(
        ['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
        completeRun=args.only is None, passed=False, gates=[])
    try:
        for name, steps in commands.items():
            if args.only and args.only != name:
                continue
            cached_path = evidence / ('partial-' + name + '.json')
            if args.resume and cached_path.is_file():
                cached = json.loads(cached_path.read_text(encoding='utf-8'))
                if cached.get('passed') and cached.get('sourceSha256') == baseline and cached.get('finalSourceSha256') == baseline and (evidence / (name + '.log')).is_file():
                    report['gates'].append(dict(cached['gates'][0], reused=True))
                    print('Reused passing ' + name + ' for identical source', flush=True)
                    continue
            started = time.monotonic()
            log = evidence / (name + '.log')
            print('Running ' + name + '; log: ' + str(log), flush=True)
            with log.open('w', encoding='utf-8') as output:
                for command in steps:
                    result = subprocess.run(command, cwd=ROOT, stdout=output,
                        stderr=subprocess.STDOUT, env=dict(os.environ, PYTHONUTF8='1'))
                    if result.returncode:
                        report['gates'].append(dict(name=name, passed=False, log=log.name))
                        raise SystemExit('Failed ' + name + '; inspect ' + str(log))
            report['gates'].append(dict(name=name, passed=True, log=log.name,
                                       seconds=round(time.monotonic() - started, 2)))
            if fingerprint() == baseline:
                cached_path.write_text(json.dumps(dict(sourceSha256=baseline,
                    finalSourceSha256=baseline, completeRun=False, passed=True,
                    gates=[report['gates'][-1]]), indent=2) + '\n', encoding='utf-8')
            print('Passed ' + name, flush=True)
        # Validation must leave tracked source unchanged.
        report['finalSourceSha256'] = fingerprint()
        if report['finalSourceSha256'] != baseline:
            raise SystemExit('Source changed during validation; rerun against the final source tree')
        report['passed'] = True
    finally:
        (evidence / ('partial-' + args.only + '.json' if args.only else 'report.json')).write_text(
            json.dumps(report, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
