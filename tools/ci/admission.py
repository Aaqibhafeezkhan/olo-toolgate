# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Fail-closed admission for quota-controlled workflow execution."""
import json
import os
from pathlib import Path
import re
from urllib.request import Request, urlopen


def decision(title, enabled=True):
    if not enabled:
        return False, 'CI automation is paused'
    if not isinstance(title, str) or not title.startswith('RC:'):
        return False, 'PR title must start with RC:'
    return True, 'RC: PR title admitted'


def merged_pr_title(repository, sha):
    if (not isinstance(repository, str)
            or not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository)
            or any(part in ('.', '..') for part in repository.split('/'))):
        return ''
    if not isinstance(sha, str) or not re.fullmatch(r'[0-9a-f]{40}', sha):
        return ''
    headers = {'Accept': 'application/vnd.github+json',
               'X-GitHub-Api-Version': '2022-11-28',
               'User-Agent': 'toolgate-ci-admission'}
    if os.environ.get('GH_TOKEN'):
        headers['Authorization'] = 'Bearer ' + os.environ['GH_TOKEN']
    page = 1
    while True:
        url = f'https://api.github.com/repos/{repository}/commits/{sha}/pulls?per_page=100&page={page}'
        with urlopen(Request(url, headers=headers), timeout=30) as response:
            pull_requests = json.load(response)
        if not isinstance(pull_requests, list):
            raise ValueError('GitHub returned an invalid pull request list')
        for pr in pull_requests:
            base = pr.get('base') or {}
            if (pr.get('merged_at') and pr.get('merge_commit_sha') == sha
                    and base.get('ref') == 'main'
                    and (base.get('repo') or {}).get('full_name') == repository):
                return pr.get('title', '')
        if len(pull_requests) < 100:
            return ''
        page += 1


def trigger_message(event, event_name):
    if event_name == 'pull_request':
        pr = event.get('pull_request') or {}
        if (pr.get('base') or {}).get('ref') != 'main':
            return ''
        return pr.get('title', '')
    repository = os.environ.get('GITHUB_REPOSITORY', '')
    if event_name == 'push':
        sha = event.get('after', '')
    elif event_name == 'workflow_dispatch':
        sha = os.environ.get('GITHUB_SHA', '')
    elif event_name == 'workflow_run':
        run = event.get('workflow_run') or {}
        if (run.get('conclusion') != 'success' or run.get('event') != 'push'
                or run.get('head_branch') != 'main'
                or (run.get('head_repository') or {}).get('full_name') != repository):
            return ''
        sha = run.get('head_sha', '')
    else:
        return ''
    return merged_pr_title(repository, sha)


def main():
    event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
    enabled = os.environ.get('CI_ENABLED') == 'true'
    title = trigger_message(event, os.environ['GITHUB_EVENT_NAME']) if enabled else ''
    allowed, reason = decision(title, enabled)
    with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as output:
        output.write('allow=' + str(allowed).lower() + '\n')
    print(reason)


if __name__ == '__main__':
    main()
