# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Resume draft prerelease uploads; development reruns replace the assets of their own release."""
import hashlib
import json
import subprocess
import time
from urllib.parse import quote


def release(run, repo, tag):
    try:
        return json.loads(run('gh', 'api', f'repos/{repo}/releases/tags/{quote(tag, safe="")}', capture=True).stdout)
    except subprocess.CalledProcessError as error:
        try:
            missing = str(json.loads(error.stdout or '{}').get('status')) == '404'
        except (ValueError, AttributeError):
            missing = False
        if missing:
            # Drafts may not yet have a Git tag, so the tag endpoint returns 404.
            pages = json.loads(run('gh', 'api', f'repos/{repo}/releases?per_page=100',
                                  '--paginate', '--slurp', capture=True).stdout)
            matches = [item for page in pages for item in page if item['tag_name'] == tag]
            if len(matches) > 1:
                raise ValueError('Multiple releases use the same tag: '+tag)
            return matches[0] if matches else None
        raise


def wait_for_release(run, repo, tag):
    """Allow GitHub's release listing to catch up after draft creation."""
    for attempt in range(6):
        current = release(run, repo, tag)
        if current is not None:
            return current
        if attempt < 5:
            time.sleep(min(2**attempt, 8))
    return None


def assets(run, repo, release_id):
    pages = json.loads(run('gh', 'api', f'repos/{repo}/releases/{release_id}/assets?per_page=100',
                          '--paginate', '--slurp', capture=True).stdout)
    result = {}
    for page in pages:
        for item in page:
            if item['name'] in result:
                raise ValueError('Duplicate remote release asset: '+item['name'])
            result[item['name']] = item
    return result


def publish(run, repo, tag, commit, folder, title, notes, prerelease=True, replace=False):
    """Uploads folder as the release tag. With replace, remote assets that differ from or are absent in folder are
    deleted and uploaded again, even on a published release; otherwise they are refused."""
    files = sorted(path for path in folder.iterdir() if path.is_file())
    expected = {path.name:('sha256:'+hashlib.sha256(path.read_bytes()).hexdigest(), path.stat().st_size) for path in files}
    if not expected or 'SHA256SUMS' not in expected:
        raise ValueError('Release assets must include a checksum manifest')
    current = release(run, repo, tag)
    if current is None:
        try:
            run('gh', 'release', 'create', tag, '--repo', repo, '--target', commit, '--draft',
                '--prerelease='+str(prerelease).lower(), '--latest=false', '--title', title, '--notes-file', str(notes))
        except subprocess.CalledProcessError:
            # A lost HTTP response can leave a successfully created draft.
            current = wait_for_release(run, repo, tag)
            if current is None:
                raise
        else:
            current = wait_for_release(run, repo, tag)
            if current is None:
                raise ValueError('Created draft release did not become visible: '+tag)
    if not current or current['target_commitish'] != commit or current['tag_name'] != tag:
        raise ValueError('Existing release commit or tag mismatch')
    if not current['draft'] and current.get('prerelease', prerelease) != prerelease:
        raise ValueError('Published release channel differs from the prepared channel')
    remote = assets(run, repo, current['id'])
    if replace:
        for name, item in remote.items():
            if name not in expected or (item.get('digest'), item.get('size')) != expected[name] or item.get('state') != 'uploaded':
                run('gh', 'api', '-X', 'DELETE', f'repos/{repo}/releases/assets/{item["id"]}')
        remote = assets(run, repo, current['id'])

    def verify(remote):
        for name, item in remote.items():
            if name not in expected or (item.get('digest'), item.get('size')) != expected[name] or item.get('state') != 'uploaded':
                raise ValueError('Existing release asset does not match prepared bytes: '+name)

    verify(remote)
    if not current['draft'] and not replace:
        if set(remote) != set(expected):
            raise ValueError('Published release has an incomplete asset set')
        return  # A completed retry must not mutate the published release.
    for path in files:
        for attempt in range(3):
            if path.name in remote:
                break
            try:
                # One file per upload avoids bulk-upload retries creating duplicate names.
                run('gh', 'release', 'upload', tag, str(path), '--repo', repo)
            except subprocess.CalledProcessError:
                remote = assets(run, repo, current['id'])
                verify(remote)
                if path.name in remote:
                    break  # Server accepted bytes before the client lost its response.
                if attempt == 2:
                    raise
                time.sleep(3*(attempt+1))
            else:
                remote = assets(run, repo, current['id'])
                verify(remote)
                if path.name in remote:
                    break
        if path.name not in remote:
            raise ValueError('Uploaded release asset is missing: '+path.name)
    if set(remote) != set(expected):
        raise ValueError('Draft release has an incomplete asset set')
    run('gh', 'release', 'edit', tag, '--repo', repo, '--draft=false', '--prerelease='+str(prerelease).lower(),
        '--latest=false', '--title', title, '--notes-file', str(notes))
