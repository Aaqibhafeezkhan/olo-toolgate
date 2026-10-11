# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Generate thin typed operation metadata from canonical Control OpenAPI.

Wire models always come from the shared published contract identity. No schemas,
validation rules, security decisions or tenant identifiers are copied into UI.
"""
import argparse
from pathlib import Path
import yaml

ROOT = Path(__file__).resolve().parents[2]
TARGET = ROOT/'apps/admin-ui/src/operations.generated.ts'


def output():
    spec = yaml.safe_load((ROOT/'packages/contracts/openapi/control-v1.yaml').read_text(encoding='utf-8'))
    operations = []
    records = {}
    for path, methods in spec['paths'].items():
        for method, operation in methods.items():
            if method not in ('get', 'post', 'put', 'patch', 'delete'): continue
            operations.append(f"  {operation['operationId']}: {{ method: '{method.upper()}', path: '{path}' }},")
            if method == 'get' and operation['operationId'].startswith('listControl'):
                kind = path.rsplit('/', 1)[1]
                words = kind.split('-')
                kind = words[0] + ''.join(word.title() for word in words[1:])
                if kind == 'audit': continue
                page = operation['responses']['200']['content']['application/json']['schema']['$ref'].split('/')[-1]
                records[kind] = page.removesuffix('Page')
    models = sorted([name for model in records.values() for name in (model,model+'Page')])
    return '\n'.join([
        '// Copyright 2026 OLO Labs', '// SPDX-License-Identifier: Apache-2.0',
        '// GENERATED FILE — DO NOT EDIT; python tools/ui/generate.py',
        "import type { " + ', '.join(models) + " } from '@olo-labs/toolgate-contracts';",
        'export const operations = {', *operations, '} as const;',
        'export interface DirectoryRecords {', *[f'  {kind}: {model};' for kind,model in sorted(records.items())], '}',
        'export interface DirectoryPages {', *[f'  {kind}: {model}Page;' for kind,model in sorted(records.items())], '}',
        'export type DirectoryKind = keyof DirectoryRecords;',
        'export const listOperations = {', *[f"  {kind}: operations.list{model}," for kind,model in sorted(records.items())], '} as const;',
        *[line for verb in ('get','create','update','delete') for line in ([f'export const {verb}Operations = {{']+[f'  {kind}: operations.{verb}{model},' for kind,model in sorted(records.items())]+['} as const;'])], '',
    ])


def main():
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('--check', action='store_true'); args = parser.parse_args()
    expected = output()
    if args.check:
        if not TARGET.is_file() or TARGET.read_bytes() != expected.encode(): raise SystemExit('UI OpenAPI operation drift; run python tools/ui/generate.py')
    else:
        TARGET.parent.mkdir(parents=True, exist_ok=True); TARGET.write_text(expected, encoding='utf-8', newline='\n')
    print('UI OpenAPI operation bindings verified' if args.check else 'UI OpenAPI operation bindings generated')


if __name__ == '__main__': main()
