# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Shared Docker Hub publication for verified and manual development releases."""
import json
import os


def publish(run, repo, commit, namespace, images, tag, aliases=(), prepare=None):
    current = json.loads(run('gh','api',f'repos/{repo}/git/ref/heads/main',capture=True).stdout)['object']['sha'] == commit
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
            for alias in [*aliases,*(['dev'] if current else [])]:
                destination = f'{namespace}/{name}:{alias}'
                if destination != target:
                    run('docker','tag',target,destination)
                    run('docker','push',destination)
    finally:
        run('docker','logout')
    return result
