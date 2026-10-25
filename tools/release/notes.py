# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Validate versioned YAML fragments and generate reproducible release assets."""
import argparse
import html
import json
from pathlib import Path
import re
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[2]
VERSION = re.compile(r'v(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?')
FILENAME = re.compile(r'([1-9][0-9]*)-[^/\\]+\.ya?ml')
TYPES = ('breaking', 'added', 'changed', 'fixed', 'security', 'docs', 'internal')


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
        version = json.loads((changes/'config.json').read_text(encoding='utf-8'))['version']
    if not isinstance(version, str) or not VERSION.fullmatch(version):
        raise ValueError('Invalid release-note version')
    folder = changes/version
    if changes.is_symlink() or folder.is_symlink() or not folder.is_dir():
        raise ValueError('Release-note collection must be a regular directory: '+str(folder))
    # Schema belongs to the controller, never to the selected historical source.
    validator = Draft202012Validator(json.loads((ROOT/'.changes/fragment.schema.json').read_text(encoding='utf-8')))
    fragments = []
    paths = list(folder.iterdir())
    for path in paths:
        if path.is_symlink() or not path.is_file() or not FILENAME.fullmatch(path.name):
            raise ValueError('Expected <number>-<contributor>.yaml: '+str(path))
    paths.sort(key=lambda p: (int(FILENAME.fullmatch(p.name)[1]), p.name))
    for path in paths:
        document = yaml_document(path)
        errors = sorted(validator.iter_errors(document), key=lambda e: str(list(e.path)))
        if errors:
            raise ValueError(f'{path}: {errors[0].message}')
        fragments.append(dict(file=path.relative_to(root).as_posix(), **document))
    if not fragments:
        raise ValueError('Release-note collection is empty: '+str(folder))
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
            lines.append('- '+prefix+text(change['summary'])+' — '+author+pr)
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
    baseline = (root/'RELEASE-NOTES.md').read_text(encoding='utf-8')
    generated = render(version, fragments) if version else ''
    (output/'RELEASE-NOTES.generated.md').write_text(generated, encoding='utf-8', newline='\n')
    (output/'contributors.json').write_text(json.dumps(dict(schemaVersion=1, version=version, fragments=fragments),
        ensure_ascii=False, indent=2, sort_keys=True)+'\n', encoding='utf-8', newline='\n')
    (output/'RELEASE-NOTES.md').write_text(baseline.rstrip()+'\n'+ ('\n'+generated if generated else ''),
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
