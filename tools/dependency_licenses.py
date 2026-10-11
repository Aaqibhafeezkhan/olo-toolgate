# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Audit resolved dependency license metadata across foundation ecosystems."""
import importlib.metadata
import json
import re
import urllib.request
import xml.etree.ElementTree as ET

from license_expression import get_spdx_licensing
from packaging.requirements import Requirement
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ALLOWED = {'Apache-2.0','MIT','MIT-0','BlueOak-1.0.0','Zlib','BSD-2-Clause','BSD-3-Clause','ISC','0BSD','Unicode-3.0','UPL-1.0','MPL-2.0','EPL-1.0','EPL-2.0','Python-2.0','PSF-2.0','Unlicense','CC0-1.0'}
TOOL_ONLY = {'MPL-2.0','EPL-1.0','EPL-2.0','Python-2.0','PSF-2.0'}
LICENSING = get_spdx_licensing()


def cargo_license(expression):
    # Cargo's historical slash syntax predates SPDX OR. Normalize only the
    # reviewed pairs found in locked dependencies, retaining metadata. Locked
    # same-file 1.0.6 and walkdir 2.5.0 README/LICENSE-MIT explicitly offer MIT
    # or Unlicense; unknown slash expressions still require review.
    return {'MIT/Apache-2.0':'MIT OR Apache-2.0',
            'Apache-2.0/MIT':'Apache-2.0 OR MIT',
            'Unlicense/MIT':'Unlicense OR MIT'}.get(expression, expression)


def accepted(expression, tooling=False):
    parsed = LICENSING.parse(expression, validate=True, strict=True)
    allowed = ALLOWED if tooling else ALLOWED - TOOL_ONLY
    # WITH is a compound symbol. It requires its own review and cannot inherit
    # approval merely because its underlying license is approved.
    decisions = {symbol: LICENSING.TRUE if getattr(symbol, 'key', str(symbol)) in allowed else LICENSING.FALSE for symbol in parsed.get_symbols()}
    return parsed.subs(decisions).simplify() == LICENSING.TRUE


def python_license(distribution):
    metadata = distribution.metadata
    expression = metadata.get('License-Expression')
    if expression: return expression
    text = metadata.get('License', '').strip()
    if text in ALLOWED: return text
    # Classifiers provide family evidence, not an exact SPDX revision.
    for classifier in metadata.get_all('Classifier', []):
        if 'License' not in classifier: continue
        for label, expression in [('Apache','Apache-2.0'),('MIT License','MIT'),('Mozilla Public License 2.0','MPL-2.0'),('Python Software Foundation','PSF-2.0')]:
            if label in classifier: return expression
        if 'BSD License' in classifier: return 'BSD family (package classifier)'
    raise ValueError(f'License metadata requires review: {distribution.metadata["Name"]}')


def maven_license(group, artifact, version, cache):
    coordinate = f'{group}:{artifact}:{version}'
    if coordinate in cache: return cache[coordinate]
    url = f'https://repo.maven.apache.org/maven2/{group.replace(".","/")}/{artifact}/{version}/{artifact}-{version}.pom'
    with urllib.request.urlopen(url, timeout=30) as response: pom = ET.fromstring(response.read())
    ns = {'m':'http://maven.apache.org/POM/4.0.0' if pom.tag.startswith('{') else ''}
    names = [node.text or '' for node in pom.findall('m:licenses/m:license/m:name', ns)]
    result = []
    for name in names:
        lowered = name.lower()
        if name in ALLOWED: result.append(name)
        elif group == 'org.bouncycastle' and name == 'Bouncy Castle Licence':
            # Upstream LICENSE.html explicitly identifies MIT; preserve upstream notices.
            # https://github.com/bcgit/bc-java/blob/main/LICENSE.html
            urls = [node.text or '' for node in pom.findall('m:licenses/m:license/m:url', ns)]
            if 'https://www.bouncycastle.org/licence.html' not in urls:
                raise ValueError('Bouncy Castle license URL requires review: '+coordinate)
            result.append('MIT')
        elif 'apache' in lowered and '2' in lowered: result.append('Apache-2.0')
        elif ('eclipse public' in lowered or lowered.startswith('epl')) and '2' in lowered: result.append('EPL-2.0')
        elif 'eclipse public' in lowered and '1' in lowered: result.append('EPL-1.0')
        elif name in ('EDL 1.0','Eclipse Distribution License - v 1.0'): result.append('BSD-3-Clause')
        elif 'classpath' in lowered or name == 'GPL2 w/ CPE': result.append('GPL-2.0-only WITH Classpath-exception-2.0')
        elif name == 'LGPL, version 2.1': result.append('LGPL-2.1-only')
        elif name == 'Universal Permissive License, Version 1.0': result.append('UPL-1.0')
        elif coordinate == 'aopalliance:aopalliance:1.0' and name == 'Public Domain':
            # The upstream declaration is public domain, not CC0. Preserve it
            # under a private SPDX reference; audit() requires the exact
            # version/scope review rather than accepting it globally.
            result.append('LicenseRef-AOP-Alliance-Public-Domain')
        elif 'public domain' in lowered:
            urls = [node.text or '' for node in pom.findall('m:licenses/m:license/m:url', ns)]
            if not any('cc0' in url or 'creativecommons.org/publicdomain/zero/1.0' in url for url in urls):
                raise ValueError('Public-domain declaration requires explicit CC0 evidence: '+coordinate)
            result.append('CC0-1.0')
        elif 'mit' in lowered: result.append('MIT')
        else: raise ValueError(f'Maven license requires review: {coordinate}: {name}')
    if not result:
        parent = pom.find('m:parent', ns)
        if parent is None: raise ValueError('No Maven license metadata: '+coordinate)
        result = [maven_license(*(parent.findtext('m:'+k, namespaces=ns) for k in ('groupId','artifactId','version')), cache)]
    cache[coordinate] = ' OR '.join(result)
    return cache[coordinate]


def audit(rust_metadata, output=None):
    inventory = []
    for package in rust_metadata['packages']:
        if package.get('source'):
            declared = package.get('license') or ''
            inventory.append({'ecosystem':'cargo','name':package['name'],'version':package['version'],'license':cargo_license(declared),'declaredLicense':declared})
    npm = json.loads((ROOT/'package-lock.json').read_text())
    for name, package in npm['packages'].items():
        if 'node_modules/' in name and not package.get('link'):
            inventory.append({'ecosystem':'npm','name':name.split('node_modules/',1)[1],'version':package['version'],'license':package.get('license') or '', 'scope':'build-test' if package.get('dev') else 'runtime'})
    pending = [Requirement(line).name for line in (ROOT/'tools/requirements.txt').read_text().splitlines() if line and not line.startswith('#')]
    visited = set()
    while pending:
        distribution = importlib.metadata.distribution(pending.pop())
        normalized = re.sub(r'[-_.]+', '-', distribution.metadata['Name']).lower()
        if normalized in visited: continue
        visited.add(normalized)
        inventory.append({'ecosystem':'python-tools','name':normalized,'version':distribution.version,'license':python_license(distribution),'scope':'build-test'})
        for spec in distribution.requires or []:
            requirement = Requirement(spec)
            if requirement.marker is None or requirement.marker.evaluate(): pending.append(requirement.name)
        if normalized == 'pip-audit': pending.extend(['filelock','platformdirs'])
    coordinates = set()
    # Quickstart's pinned runtime Python packages are not installed in the host
    # tooling venv. Audit primary package metadata without importing runtime code.
    for line in (ROOT/'apps/quickstart/requirements.txt').read_text().splitlines():
        if not line or line.startswith('#'): continue
        name, version = line.split('==')
        with urllib.request.urlopen(f'https://pypi.org/pypi/{name}/{version}/json',timeout=30) as response:
            info = json.load(response)['info']
        expression = info.get('license_expression')
        if not expression: raise ValueError('Runtime Python license requires review: '+name)
        inventory.append({'ecosystem':'python-runtime','name':info['name'],'version':version,
                          'license':expression,'scope':'runtime'})
    runtime_coordinates = set()
    for lock in ROOT.rglob('gradle.lockfile'):
        if any(part in ('.dev','.gradle','node_modules','target') for part in lock.relative_to(ROOT).parts): continue
        for line in lock.read_text().splitlines():
            if line and not line.startswith('#') and line.count(':') == 2:
                coordinates.add(line.split('=')[0])
                if 'runtimeClasspath' in line.split('=')[1].split(','): runtime_coordinates.add(line.split('=')[0])
    reviews = json.loads((ROOT/'tools/dependency-license-reviews.json').read_text())['reviews']
    cache = {}
    for coordinate in sorted(coordinates):
        group, artifact, version = coordinate.split(':')
        inventory.append({'ecosystem':'maven','name':f'{group}:{artifact}','version':version,'license':maven_license(group,artifact,version,cache),
                          'scope':'runtime' if coordinate in runtime_coordinates else 'build-test'})
    for dependency in inventory:
        expression = dependency['license']
        tooling = dependency['ecosystem'] == 'python-tools' or dependency.get('scope') == 'build-test' or dependency['name'].startswith(('org.junit','org.opentest4j','org.apiguardian'))
        coordinate = dependency['name']+':'+dependency['version']
        review = reviews.get(coordinate+':'+str(dependency.get('scope'))) or reviews.get(coordinate)
        reviewed = review and review['expression'] == expression and review['scope'] == dependency.get('scope')
        if reviewed: dependency['review'] = review
        if expression != 'BSD family (package classifier)' and not reviewed and not accepted(expression, tooling):
            raise ValueError(f'Dependency license rejected: {dependency["name"]}: {expression}')
    inventory.sort(key=lambda d:(d['ecosystem'],d['name']))
    output = output or ROOT/'build/release/dependency-licenses.json'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({'policy':'Permissive runtime dependencies; MPL/EPL build/test tooling; exact-version/scope reviewed Jakarta/Parsson EPL APIs and classfile build tooling; BSD classifier evidence retained','allowedSpdx':sorted(ALLOWED),'dependencies':inventory},indent=2)+'\n',encoding='utf-8',newline='\n')
    print(f'Resolved dependency license audit passed: {len(inventory)} entries; {output}')
