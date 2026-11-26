# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Prepare contributor collections from pinned Git trees without rewriting credits."""
import base64
import json
from pathlib import Path
import tempfile

from tools.release import notes, prepare


def entries(source, source_version, version, root_tree):
    collection = 'main' if source == 'main' else 'v'+'.'.join(source_version.split('.')[:2])
    destination = 'v'+'.'.join(version.split('.')[:2])

    def directory(sha):
        document = prepare.api('git/trees/'+sha)
        if document.get('truncated'):
            raise ValueError('Contributor tree listing is truncated')
        return {item['path']: item for item in document['tree']}

    def subtree(items, name):
        item = items.get(name)
        if not item or item['type'] != 'tree' or item['mode'] != '040000':
            raise ValueError('Contributor collection must be a regular directory: '+name)
        return item['sha']

    root = directory(root_tree)
    changes = directory(subtree(root, '.changes'))
    source_tree = subtree(changes, collection)
    files = directory(source_tree)
    # Validate the exact pinned YAML with the controller's canonical schema.
    with tempfile.TemporaryDirectory(prefix='release-contributors-') as temporary:
        target = Path(temporary)/'.changes'/collection
        target.mkdir(parents=True)
        for name, item in files.items():
            if (item['type'] != 'blob' or item['mode'] not in ('100644', '100755')
                    or not notes.FILENAME.fullmatch(name)):
                raise ValueError('Expected a regular numbered contributor fragment: '+name)
            blob = prepare.api('git/blobs/'+item['sha'])
            if blob['encoding'] != 'base64':
                raise ValueError('Unsupported contributor blob encoding')
            (target/name).write_bytes(base64.b64decode(blob['content']))
        notes.load(Path(temporary), collection)
    existing = changes.get(destination)
    if existing and (existing['type'] != 'tree' or existing['mode'] != '040000' or existing['sha'] != source_tree):
        raise ValueError('Target contributor collection conflicts with the source: '+destination)
    result = [dict(path='.changes/'+destination, mode='040000', type='tree', sha=source_tree),
              dict(path='.changes/config.json', mode='100644', type='blob',
                   content=json.dumps(dict(version=destination), indent=2)+'\n')]
    if 'main' in changes:
        subtree(changes, 'main')  # Refuse a malformed staging path before deleting it in the new branch.
        result.append(dict(path='.changes/main', mode='040000', type='tree', sha=None))
    return result
