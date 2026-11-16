# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Privileged portable profile bundles, including exact images and model bytes."""
import hashlib
import json
import re
from pathlib import Path, PurePosixPath
import shutil
import stat
import subprocess
import tempfile
import zipfile

import configuration

MAX_BUNDLE = 12 * 1024 ** 3
MAX_FILES = 1024
REGISTRY_IMAGE = 'registry:3.0.0@sha256:6c5666b861f3505b116bb9aa9b25175e71210414bd010d92035ff64018f9457e'


def linked(path):
    return path.is_symlink() or getattr(path, 'is_junction', lambda: False)()


def image_references(settings):
    deployment = settings['deployment']
    references = {deployment['platform']['quickstartImage'], deployment['platform']['deviceImage'],
                  deployment['runtime']['image'], deployment['model']['image'], REGISTRY_IMAGE}
    if deployment['monitoring']['enabled']:
        references.update(deployment['monitoring']['images'].values())
    return references


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def docker(*args):
    result = subprocess.run(['docker', *args], check=True, capture_output=True, text=True, timeout=1800)
    return result.stdout.strip()


def export(root, name, output):
    if output.suffix != '.olo':
        raise ValueError('Portable profile bundles use the .olo extension')
    source = root / 'configurations' / name
    if linked(source) or linked(root / 'configurations') or linked(root / 'models'):
        raise ValueError('Portable export requires unlinked configuration and model directories')
    settings = configuration.load(source)
    deployment = settings['deployment']
    references = image_references(settings)
    images = {ref: docker('image', 'inspect', ref, '--format', '{{.Id}}') for ref in sorted(references)}
    model = root / 'models' / deployment['model']['modelFile']
    if (linked(source) or linked(root / 'configurations') or linked(root / 'models')
            or linked(model) or not model.is_file()):
        raise ValueError('The declared model file must exist before portable export')
    files = {}
    for path in sorted(source.rglob('*')):
        relative = path.relative_to(source)
        if linked(path):
            raise ValueError('Configuration links cannot be exported')
        if not path.is_file():
            continue
        if relative.parts[0] not in ('workflow-profile.json', 'example.txt', 'graphs', 'packages'):
            continue
        files['configuration/' + relative.as_posix()] = path
    files['models/' + model.name] = model
    output.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output.parent) as directory:
        image_archive = Path(directory) / 'images.tar'
        docker('image', 'save', '--output', str(image_archive), *sorted(images))
        files['images.tar'] = image_archive
        if len(files) >= MAX_FILES or sum(p.stat().st_size for p in files.values()) > MAX_BUNDLE:
            raise ValueError('Portable bundle exceeds the 12 GiB / 1024-file limit')
        manifest = dict(format=1, images=images, files={key: sha(path) for key, path in files.items()},
                        credentials='Regenerated locally; provide vendor secrets separately')
        try:
            with zipfile.ZipFile(output, 'x', compression=zipfile.ZIP_DEFLATED, compresslevel=6, allowZip64=True) as archive:
                for key, path in files.items():
                    archive.write(path, key)
                archive.writestr('bundle.json', json.dumps(manifest, sort_keys=True))
            if output.stat().st_size > MAX_BUNDLE:
                raise ValueError('Compressed bundle exceeds the 12 GiB upload limit')
        except Exception:
            output.unlink(missing_ok=True)
            raise
    return output


def inspect(archive):
    entries = archive.infolist()
    if len(entries) > MAX_FILES or sum(item.file_size for item in entries) > MAX_BUNDLE:
        raise ValueError('Portable bundle exceeds limits')
    names = [item.filename for item in entries]
    if len(set(names)) != len(names) or 'bundle.json' not in names:
        raise ValueError('Duplicate entries or missing bundle manifest')
    for item in entries:
        path = PurePosixPath(item.filename)
        mode = item.external_attr >> 16
        if (str(path) != item.filename or path.is_absolute() or '..' in path.parts or '\\' in item.filename
                or ':' in item.filename or not path.parts or item.is_dir()
                or stat.S_IFMT(mode) not in (0, stat.S_IFREG)
                or item.flag_bits & 1):
            raise ValueError('Unsupported bundle path or file type')
        if item.filename not in ('bundle.json', 'images.tar') and path.parts[0] not in ('configuration', 'models'):
            raise ValueError('Unsupported bundle content')
    if archive.getinfo('bundle.json').file_size > 262144:
        raise ValueError('Unbounded bundle manifest')
    manifest = json.loads(archive.read('bundle.json'))
    if (set(manifest) != {'format', 'images', 'files', 'credentials'} or manifest['format'] != 1
            or not isinstance(manifest['images'], dict) or not 1 <= len(manifest['images']) <= 16
            or not isinstance(manifest['files'], dict) or set(manifest['files']) != set(names) - {'bundle.json'}
            or not {'images.tar', 'configuration/workflow-profile.json'} <= set(names)):
        raise ValueError('Unsupported portable bundle manifest')
    for ref, identity in manifest['images'].items():
        configuration.image(ref)
        if not isinstance(identity, str) or not re.fullmatch(r'sha256:[a-f0-9]{64}', identity):
            raise ValueError('Invalid bundled image identity')
    return manifest


def install(root, name, bundle):
    if bundle.suffix != '.olo':
        raise ValueError('Import a compressed .olo profile bundle')
    if not configuration.NAME.fullmatch(name):
        raise ValueError('Use a configuration name without paths')
    target = root / 'configurations' / name
    if target.exists() or linked(target) or linked(root / 'configurations') or linked(root / 'models'):
        raise ValueError('Import requires a new configuration name and unlinked storage')
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=target.parent) as directory:
        staged = Path(directory)
        with zipfile.ZipFile(bundle) as archive:
            manifest = inspect(archive)
            for relative, digest in manifest['files'].items():
                path = staged / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(relative) as source, path.open('xb') as destination:
                    shutil.copyfileobj(source, destination, 1024 * 1024)
                if sha(path) != digest:
                    raise ValueError('Bundle content digest mismatch')
        settings = configuration.load(staged / 'configuration')
        if set(manifest['images']) != image_references(settings):
            raise ValueError('Bundled image inventory differs from the declared deployment')
        model_name = settings['deployment']['model']['modelFile']
        model = staged / 'models' / model_name
        if not model.is_file() or {p.name for p in (staged / 'models').iterdir()} != {model_name}:
            raise ValueError('Bundle must contain its exact declared model')
        destination = root / 'models' / model_name
        if linked(destination) or destination.exists() and sha(destination) != sha(model):
            raise ValueError('Existing model has different content; use a distinct model filename')
        # All file validation precedes this privileged image operation.
        docker('image', 'load', '--input', str(staged / 'images.tar'))
        for ref, identity in manifest['images'].items():
            if '@sha256:' in ref:
                # Docker archives don't reliably restore repository digest associations.
                # Resolve the exact exported immutable registry reference before inspecting it.
                try:
                    docker('image', 'inspect', ref, '--format', '{{.Id}}')
                except subprocess.CalledProcessError:
                    docker('pull', ref)
            if docker('image', 'inspect', ref, '--format', '{{.Id}}') != identity:
                raise ValueError('Imported image identity differs from exported image')
        destination.parent.mkdir(parents=True, exist_ok=True)
        if not destination.exists():
            shutil.copyfile(model, destination)
        folders = settings['fileAgent']
        source = settings['deployment']['source']
        note = ('<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->\n'
                f'# Imported workflow: {settings["workflowId"]}\n\n'
                f'Configuration directory: `{name}`. Generated storage is `../../state/{name}/`.\n'
                f'Trigger: `{source["triggerId"]}` from `{source["packageId"]}` version `{source["version"]}`.\n\n'
                'The trigger configuration is:\n\n```json\n'+json.dumps(source['config'],indent=2)+'\n```\n\n')
        if folders:
            note += (f'Place input files in `../../data/{name}/{folders["inbox"]}/`; '
                     f'check `../../data/{name}/{folders["output"]}/` for output.\n')
        else:
            note += f'Inspect Control workflow results and `../../state/{name}/workflow/results/` for output.\n'
        note += ('Activate this configuration through WorkflowStack administration at http://127.0.0.1:48190/. '
                 'The portal shows the console and private admin credential location.\n')
        (staged / 'configuration/README.md').write_text(note, encoding='utf-8')
        (staged / 'configuration').rename(target)
    return name
