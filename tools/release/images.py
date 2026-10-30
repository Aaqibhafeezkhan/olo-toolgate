# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Shared Docker Hub publication for verified and manual development releases."""
import json
import os
from urllib.parse import quote


def current_head(run, repo, branch, commit):
    return json.loads(run('gh', 'api', f'repos/{repo}/git/ref/heads/'+quote(branch, safe=''),
                          capture=True).stdout)['object']['sha'] == commit


def publish(run, repo, commit, namespace, images, tag, aliases=(), prepare=None,
            source_branch=None, branch_alias=None):
    current = current_head(run, repo, source_branch or 'main', commit)
    run('docker','login','--username',os.environ['DOCKERHUB_USERNAME'],'--password-stdin',input=os.environ['DOCKERHUB_TOKEN'])
    result = {}
    try:
        for component,(local,name) in images.items():
            if prepare:
                prepare(component)
            target = f'{namespace}/{name}:{tag}'
            run('docker','tag',local,target)
            run('docker','push',target)
            result[component] = run('docker','inspect',target,'--format','{{index .RepoDigests 0}}',capture=True).stdout.strip()
            if source_branch is not None:
                current = current_head(run, repo, source_branch, commit)
            for alias in [*aliases, *([branch_alias] if branch_alias and current else []),
                          *(['dev-branch'] if source_branch not in (None, 'main') and not source_branch.startswith('release/') else []),
                          *(['dev'] if current and source_branch in (None, 'main') else [])]:
                destination = f'{namespace}/{name}:{alias}'
                if destination != target:
                    run('docker','tag',target,destination)
                    run('docker','push',destination)
    finally:
        run('docker','logout')
    return result
