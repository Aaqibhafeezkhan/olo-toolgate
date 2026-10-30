# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Render the public release description from verified release metadata."""
import os
from pathlib import Path
import re
from string import Template


def render(info, fragments, registries=None, repository=None):
    from tools.release.notes import render as contributions
    version = info['version']
    if not re.fullmatch(r'\d+\.\d+\.\d+(?:-(?:alpha|beta|rc|dev(?:-[a-z0-9]+(?:-[a-z0-9]+)*)?)\.\d+)?', version):
        raise ValueError('Invalid release-page version')
    repository = repository or os.environ.get('GITHUB_REPOSITORY', 'olo-labs/olo-toolgate')
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
        raise ValueError('Invalid release-page repository')
    commit = info.get('commit') or info.get('sourceCommit')
    if not re.fullmatch(r'[0-9a-f]{40}', commit or ''):
        raise ValueError('Invalid release-page source commit')
    repo = 'https://github.com/'+repository
    status = 'Stable Release' if '-' not in version else ('Alpha Release' if '-alpha.' in version else 'Beta Release' if '-beta.' in version else 'Release Candidate' if '-rc.' in version else 'Development Release')
    images = (registries or {}).get('images', {})
    mirrors = (registries or {}).get('dockerhub', {})
    rows = []
    for component, reference in images.items():
        rows.append('| '+component.replace('_', ' ').title()+' | `'+reference+'` |'+(' `'+mirrors[component]+'` |' if component in mirrors else ' — |'))
    image_table = ('| Component | GHCR digest | Docker Hub digest |\n|---|---|---|\n'+'\n'.join(rows)) if rows else 'Image publication destinations and digests are recorded in `registry-publication.json`.'
    return Template(Path(__file__).with_name('page-template.md').read_text(encoding='utf-8')).substitute(
        version=version, status=status, repo=repo, tag='v'+version, commit=commit,
        quickstart='ghcr.io/'+repository.split('/')[0].lower()+'/olo-toolgate-quickstart:'+version,
        images=image_table, contributions=contributions('v'+version, fragments).strip())
