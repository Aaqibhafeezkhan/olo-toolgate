# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Synchronize release metadata from the canonical contract and product versions."""
import argparse
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
# Numeric prerelease identifiers follow the same no-leading-zero rule as the core.
# Release versions omit build metadata so every configured registry uses one tag.
NUMERIC = r'(?:0|[1-9][0-9]*)'
PRERELEASE = r'(?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)'
SEMVER = re.compile(rf'{NUMERIC}\.{NUMERIC}\.{NUMERIC}(?:-{PRERELEASE}(?:\.{PRERELEASE})*)?')


def release_notes(root=None):
    """Render current notes at release time without modifying tracked snapshots."""
    root = ROOT if root is None else root
    product = (root/'VERSION').read_text(encoding='utf-8').strip()
    contracts = (root/'packages/contracts/VERSION').read_text(encoding='utf-8').strip()
    return ("<!-- GENERATED FILE: tools/contracts/version.py; do not edit -->\n"
        + f"# OLO ToolGate {product} release notes\n\nContracts version: `{contracts}`.\n\n"
        + "License: Apache-2.0. See LICENSE and bundled third-party notices/SBOMs.\n\n"
        + (root/'CHANGELOG.md').read_text(encoding='utf-8'))


def expected():
    contracts = (ROOT/'packages/contracts/VERSION').read_text(encoding='utf-8').strip()
    product = (ROOT/'VERSION').read_text(encoding='utf-8').strip()
    if not SEMVER.fullmatch(contracts) or not SEMVER.fullmatch(product):
        raise ValueError('Release version must be SemVer core/prerelease without build metadata')
    outputs = {}
    manifest = ROOT/'packages/contracts/contract-set.yaml'
    outputs[manifest] = re.sub(r'^version: .*', f'version: {contracts}', manifest.read_text(encoding='utf-8'), flags=re.M)
    cargo = ROOT/'Cargo.toml'
    for lock in sorted((ROOT/'apps').glob('*/gradle-artifact.lockfile')):
        outputs[lock]=re.sub(r'(?m)^(io\.github\.olo-labs:toolgate-contracts:)[^=]+=',lambda m:m[1]+contracts+'=',lock.read_text(encoding='utf-8'))
    # Rust applications ship with the product; the contracts crate carries the contract-set version.
    outputs[cargo] = re.sub(r'^version = "[^"]+"', f'version = "{product}"', cargo.read_text(encoding='utf-8'), flags=re.M)
    outputs[cargo] = re.sub(r'(olo-toolgate-contracts = \{[^\n]*version = ")[^"]+', lambda m: m[1]+contracts, outputs[cargo])
    crate = ROOT/'packages/contracts/rust/Cargo.toml'
    outputs[crate] = re.sub(r'^version(?:\.workspace = true| = "[^"]+")', f'version = "{contracts}"', crate.read_text(encoding='utf-8'), flags=re.M)
    for path in ('package.json','packages/contracts/typescript/package.json','apps/admin-ui/package.json'):
        p = ROOT/path
        content = json.loads(p.read_text(encoding='utf-8'))
        content['version'] = contracts if 'contracts' in path else product
        if path == 'apps/admin-ui/package.json':
            content['dependencies']['@olo-labs/toolgate-contracts'] = contracts
        outputs[p] = json.dumps(content, indent=2) + '\n'
    for example in (ROOT/'core-mcp').glob('*/toolgate.json'):
        content = json.loads(example.read_text(encoding='utf-8'))
        if 'sdkVersion' in content:
            content['sdkVersion'] = product
            outputs[example] = json.dumps(content, indent=2) + '\n'
    chart = ROOT/'deploy/helm/olo-toolgate/Chart.yaml'
    outputs[chart] = re.sub(r'^version: .*', f'version: {product}', chart.read_text(encoding='utf-8'), flags=re.M)
    outputs[chart] = re.sub(r'^appVersion: .*', f'appVersion: "{product}"', outputs[chart], flags=re.M)
    python_project = ROOT/'tool-sdk/python-sdk/pyproject.toml'
    if python_project.is_file():
        core, _, prerelease = product.partition('-')
        match = re.fullmatch(r'(dev|alpha|beta|rc)(?:\.(\d+))?', prerelease)
        suffix = {'dev': '.dev', 'alpha': 'a', 'beta': 'b', 'rc': 'rc'}
        pep440 = core if not prerelease else core + (suffix[match[1]] + (match[2] or '0') if match else '.dev0+' + prerelease)
        outputs[python_project] = re.sub(r'^version = "[^"]+"', f'version = "{pep440}"', python_project.read_text(encoding='utf-8'), flags=re.M)
        for example in (ROOT/'core-mcp').glob('*/pyproject.toml'):
            outputs[example] = re.sub(r'toolgate-sdk==[^"\s]+', 'toolgate-sdk==' + pep440,
                                      example.read_text(encoding='utf-8'))
        python_version = ROOT/'tool-sdk/python-sdk/src/toolgate/_version.py'
        source = python_version.read_text(encoding='utf-8')
        source = re.sub(r'^SDK_VERSION = .*', f'SDK_VERSION = "{product}"', source, flags=re.M)
        outputs[python_version] = re.sub(r'^CONTRACTS_VERSION = .*', f'CONTRACTS_VERSION = "{contracts}"', source, flags=re.M)
    gateway_api = ROOT/'packages/contracts/openapi/gateway-v1.yaml'
    if gateway_api.exists():
        outputs[gateway_api] = re.sub(r'^  version: .*', f'  version: {product}', gateway_api.read_text(encoding='utf-8'), flags=re.M)
    control_api = ROOT/'packages/contracts/openapi/control-v1.yaml'
    if control_api.exists():
        outputs[control_api] = re.sub(r'^  version: .*', f'  version: {product}', control_api.read_text(encoding='utf-8'), flags=re.M)
    quickstart_api = ROOT/'packages/contracts/openapi/quickstart-v1.yaml'
    if quickstart_api.exists():
        outputs[quickstart_api] = re.sub(r'^  version: .*', f'  version: {product}', quickstart_api.read_text(encoding='utf-8'), flags=re.M)
    return outputs


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--set', dest='version')
    args = parser.parse_args()
    if args.version:
        if args.check or not SEMVER.fullmatch(args.version):
            parser.error('--set requires a valid SemVer and cannot accompany --check')
        for path in ('VERSION','packages/contracts/VERSION'):
            (ROOT/path).write_text(args.version+'\n', encoding='utf-8', newline='\n')
    drift = []
    for path, content in expected().items():
        if not path.exists() or path.read_bytes() != content.encode('utf-8'):
            drift.append(path.relative_to(ROOT).as_posix())
            if not args.check:
                path.write_text(content, encoding='utf-8', newline='\n')
    if args.check and drift:
        raise SystemExit('Version drift: ' + ', '.join(drift))
    print('Version metadata verified' if args.check else 'Version metadata synchronized; regenerate bindings next')


if __name__ == '__main__':
    main()
