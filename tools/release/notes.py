# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Validate versioned YAML fragments and generate reproducible release assets."""
import argparse
import html
import json
from pathlib import Path
import re
import sys
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
VERSION = re.compile(r'v(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?')
SERIES = re.compile(r'v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)')
FILENAME = re.compile(r'([1-9][0-9]*)-[^/\\]+\.ya?ml')
TYPES = ('breaking', 'added', 'changed', 'fixed', 'security', 'docs', 'internal')
AI_IDENTITY = re.compile(r'(?i)(?:openai|codex|chatgpt|github[\s_-]*copilot|claude[\s_-]*(?:code|ai)|gemini[\s_-]*(?:cli|ai))')
PEOPLE_FIELDS = {'contributor', 'contributors', 'owners', 'owner', 'reviewers', 'summaryAuthor'}


def human_attribution(value, path):
    """Reject assistant credit, while permitting product names in change descriptions."""
    if isinstance(value, list):
        for item in value:
            human_attribution(item, path)
    elif isinstance(value, dict):
        for key, item in value.items():
            if key in PEOPLE_FIELDS:
                people = item if isinstance(item, list) else [item]
                for person in people:
                    if isinstance(person, dict) and any(AI_IDENTITY.search(str(person.get(field, ''))) for field in ('name', 'github')):
                        raise ValueError(f'{path}: AI identities cannot receive contributor attribution')
            human_attribution(item, path)


def yaml_document(path):
    import yaml

    class UniqueLoader(yaml.SafeLoader):
        pass

    def mapping(loader, node):
        result = {}
        for key_node, value_node in node.value:
            key = loader.construct_object(key_node)
            if not isinstance(key, str) or key in result:
                raise ValueError(f'{path}: duplicate or non-string YAML key: {key}')
            result[key] = loader.construct_object(value_node)
        return result

    UniqueLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, mapping)
    try:
        return yaml.load(path.read_text(encoding='utf-8'), Loader=UniqueLoader)
    except yaml.YAMLError as error:
        raise ValueError(f'{path}: invalid YAML: {error}') from error


def load(root, version=None):
    from jsonschema import Draft202012Validator

    changes = root/'.changes'
    if version is None:
        # Controller tools may build historical branches predating fragments.
        if not changes.exists():
            return None, []
        manifest = root/'release.json'
        version = ('v'+json.loads(manifest.read_text(encoding='utf-8'))['version'] if manifest.is_file()
                   else json.loads((changes/'config.json').read_text(encoding='utf-8'))['version'])
    if not isinstance(version, str) or not (VERSION.fullmatch(version) or SERIES.fullmatch(version)):
        raise ValueError('Invalid release-note version')
    series = '.'.join(version.split('.')[:2])
    folders = [changes/series]
    if version != series:
        folders.append(changes/version)
    folders = [folder for folder in folders if folder.exists() or folder.is_symlink()]
    if not folders or changes.is_symlink() or any(folder.is_symlink() or not folder.is_dir() for folder in folders):
        raise ValueError('Release-note collection must be a regular directory: '+str(changes/version))
    # Schema belongs to the controller, never to the selected historical source.
    validator = Draft202012Validator(json.loads((ROOT/'.changes/fragment.schema.json').read_text(encoding='utf-8')))
    fragments = []
    paths = [path for folder in folders for path in folder.iterdir()]
    for path in paths:
        if path.is_symlink() or not path.is_file() or not FILENAME.fullmatch(path.name):
            raise ValueError('Expected <number>-<contributor>.yaml: '+str(path))
    paths.sort(key=lambda p: (int(FILENAME.fullmatch(p.name)[1]), p.name, p.parent.name))
    for path in paths:
        document = yaml_document(path)
        errors = sorted(validator.iter_errors(document), key=lambda e: str(list(e.path)))
        if errors:
            raise ValueError(f'{path}: {errors[0].message}')
        if AI_IDENTITY.search(path.stem):
            raise ValueError(f'{path}: AI identities cannot name contributor fragments')
        human_attribution(document, path)
        fragments.append(dict(file=path.relative_to(root).as_posix(), **document))
    if not fragments:
        raise ValueError('Release-note collection is empty: '+str(changes/version))
    return version, fragments


def text(value):
    """Render contributor text literally, without active Markdown/HTML."""
    value = html.escape(' '.join(value.split()), quote=False)
    return re.sub(r'([\\`*_{}\[\]()#+.!|>-])', r'\\\1', value)


def person_text(person):
    roles = ' ('+', '.join(role.replace('-', ' ') for role in person['roles'])+')' if 'roles' in person else ''
    return text(person['name'])+(' (@'+person['github']+')' if 'github' in person else '')+roles


def people_text(people):
    # Preserve author order while crediting an identical record only once.
    return ', '.join(dict.fromkeys(person_text(person) for person in people))


def render(version, fragments):
    lines = ['## Contributor changes for '+version, '']
    for kind in TYPES:
        entries = [(fragment, change) for fragment in fragments for change in fragment['changes'] if change['type'] == kind]
        if not entries:
            continue
        lines += ['### '+kind.title(), '']
        for fragment, change in entries:
            author = people_text([fragment['contributor']]+change.get('contributors', []))
            prefix = text(change['component'])+': ' if 'component' in change else ''
            references = change.get('pullRequests', [])+([change['pr']] if 'pr' in change else [])
            prs = sorted({reference if isinstance(reference, int) else reference['number'] for reference in references})
            pr = ' ('+', '.join('#'+str(number) for number in prs)+')' if prs else ''
            lines.append('- '+prefix+text(change['summary'])+' â€” '+author+pr)
            for field in ('impact', 'migration'):
                if field in change:
                    lines.append('  - '+field.title()+': '+text(change[field]))
            if 'audience' in change:
                lines.append('  - Audience: '+', '.join(change['audience']))
            if 'owners' in change:
                lines.append('  - Owners: '+people_text(change['owners']))
            for reference in sorted((item for item in references if isinstance(item, dict)), key=lambda item: item['number']):
                label = '#'+str(reference['number'])
                if 'url' in reference:
                    label = '['+label+']('+quote(reference['url'], safe=':/?#=&%.-_~')+')'
                lines.append('  - PR '+label+(': '+text(reference['title']) if 'title' in reference else ''))
                for field in ('owners', 'contributors', 'reviewers'):
                    if field in reference:
                        lines.append('    - '+field.title()+': '+people_text(reference[field]))
            for ticket in change.get('tickets', []):
                # Schema limits references to HTTPS; encode Markdown delimiters.
                reference = ticket['system'].upper()+' ['+text(ticket['id'])+']('+quote(ticket['url'], safe=':/?#=&%.-_~')+')'
                lines.append('  - Ticket: '+reference)
                if 'summary' in ticket:
                    lines.append('    - Summary: '+text(ticket['summary']))
                for field, label in [('owner', 'Owner'), ('summaryAuthor', 'Summary by')]:
                    if field in ticket:
                        lines.append('    - '+label+': '+person_text(ticket[field]))
                if 'contributors' in ticket:
                    lines.append('    - Contributors: '+people_text(ticket['contributors']))
        lines.append('')
    return '\n'.join(lines)+'\n'


def generate(root, output, version=None):
    version, fragments = load(root, version)
    output.mkdir(parents=True, exist_ok=True)
    if all((root/path).is_file() for path in ('VERSION', 'packages/contracts/VERSION', 'CHANGELOG.md')):
        from tools.contracts.version import release_notes
        baseline = release_notes(root)
    else:
        baseline = (root/'RELEASE-NOTES.md').read_text(encoding='utf-8')
    generated = render(version, fragments) if version else ''
    (output/'RELEASE-NOTES.generated.md').write_text(generated, encoding='utf-8', newline='\n')
    (output/'contributors.json').write_text(json.dumps(dict(schemaVersion=1, version=version, fragments=fragments),
        ensure_ascii=False, indent=2, sort_keys=True)+'\n', encoding='utf-8', newline='\n')
    page = baseline.rstrip()+'\n'+ ('\n'+generated if generated else '')
    manifest = root/'release.json'
    if manifest.is_file():
        info = json.loads(manifest.read_text(encoding='utf-8'))
        if info.get('sourceCommit') or info.get('commit'):
            from tools.release.page import render as render_page
            page = render_page(info, fragments)
    (output/'RELEASE-NOTES.md').write_text(page,
        encoding='utf-8', newline='\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, default=ROOT)
    parser.add_argument('--version')
    parser.add_argument('--output', type=Path, default=ROOT/'build/release-notes')
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    if args.check:
        load(args.source, args.version)
    else:
        generate(args.source, args.output, args.version)


if __name__ == '__main__':
    main()
