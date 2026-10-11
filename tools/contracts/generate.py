# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Deterministic structural bindings for the documented foundation schema subset.

Canonical JSON Schema validation remains mandatory at untrusted input boundaries.
This generator deliberately fails on unsupported structural types rather than
silently emitting untyped models. No timestamps or machine paths enter outputs.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / 'packages/contracts'
HEADER = 'Copyright 2026 OLO Labs\nSPDX-License-Identifier: Apache-2.0\nGENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py'


def source_sha256(path):
    """Hash canonical LF text, independent of checkout newline conversion."""
    return hashlib.sha256(path.read_text(encoding="utf-8").encode("utf-8")).hexdigest()


VERSIONS = ('v1', 'v2')


def definitions(ver='v1'):
    result = {}
    for path in sorted((CONTRACTS / 'schemas' / ver).glob('*.json')):
        for name, schema in json.loads(path.read_text())['$defs'].items():
            if name in result:
                raise ValueError(f'Duplicate definition: {name}')
            result[name] = schema
            validate_shape(schema)
    return result


def validate_shape(schema):
    """Refuse structural schema composition this generator cannot represent."""
    unsupported = {'oneOf','anyOf','allOf','if','then','else','not','patternProperties','dependentSchemas','unevaluatedProperties'} & schema.keys()
    if unsupported:
        raise ValueError('Unsupported structural schema keywords: ' + ', '.join(sorted(unsupported)))
    for child in schema.get('properties', {}).values(): validate_shape(child)
    if 'items' in schema: validate_shape(schema['items'])


def named(schema):
    return schema['$ref'].rsplit('/', 1)[1]


def field_type(schema, language, defs):
    if '$ref' in schema:
        name = named(schema)
        target = defs[name]
        if target.get('type') == 'object' or 'enum' in target:
            return name
        return field_type(target, language, defs)
    if 'enum' in schema:
        raise ValueError('Inline enums must be named in $defs')
    if 'type' not in schema:
        # A typeless property (typed defaults, effective values) carries any JSON value.
        return {'java':'com.fasterxml.jackson.databind.JsonNode','rust':'serde_json::Value','ts':'unknown','php':'mixed'}[language]
    kind = schema['type']
    primitives = {
        'java': {'string':'String','integer':'Long','number':'Double','boolean':'Boolean','object':'java.util.Map<String, com.fasterxml.jackson.databind.JsonNode>'},
        'rust': {'string':'String','integer':'u64','number':'f64','boolean':'bool','object':'std::collections::BTreeMap<String, serde_json::Value>'},
        'ts': {'string':'string','integer':'number','number':'number','boolean':'boolean','object':'Record<string, unknown>'},
        'php': {'string':'string','integer':'int','number':'float','boolean':'bool','object':'\\stdClass'},
    }
    if kind == 'array':
        inner = field_type(schema['items'], language, defs)
        return {'java':f'java.util.List<{inner}>','rust':f'Vec<{inner}>','ts':f'ReadonlyArray<{inner}>','php':'array'}[language]
    if kind == 'object' and 'properties' in schema:
        raise ValueError('Inline model must be named in $defs')
    return primitives[language][kind]


def snake(name):
    return re.sub(r'(?<!^)(?=[A-Z])', '_', name).lower()


def php_value(schema, expr, defs):
    if '$ref' in schema:
        name = named(schema)
        target = defs[name]
        if 'enum' in target:
            return f'{name}::from({expr})'
        if target.get('type') == 'object':
            return f'{name}::fromArray({expr})'
        return php_value(target, expr, defs)
    if 'type' not in schema:
        return expr
    if schema['type'] == 'array':
        item = php_value(schema['items'], '$item', defs)
        return f'array_map(static fn ($item) => {item}, {expr})'
    if schema['type'] == 'object':
        return f'(object) {expr}'
    return expr


def enum_member(value, language):
    """Language identifier for an enum wire value; values such as `control-breakglass` are renamed."""
    if language == 'rust':
        return ''.join(part.title() for part in re.split(r'[_-]', value))
    return value.replace('-', '_')


JAVA_KEYWORDS = {'abstract','assert','boolean','break','byte','case','catch','char','class','const','continue','default','do','double','else','enum','extends','final','finally','float','for','goto','if','implements','import','instanceof','int','interface','long','native','new','package','private','protected','public','return','short','static','strictfp','super','switch','synchronized','this','throw','throws','transient','try','void','volatile','while','true','false','null'}


def java_ident(key):
    """Record component name; Java keywords such as `default` and `enum` get a trailing underscore."""
    return key + '_' if key in JAVA_KEYWORDS else key


def const_literal(value, language):
    if isinstance(value, str):
        return {'java': json.dumps(value), 'rust': json.dumps(value), 'php': "'" + value + "'", 'ts': json.dumps(value)}[language]
    return {'java': f'{value}L', 'rust': str(value), 'php': str(value), 'ts': str(value)}[language]


def render_version(ver, version, outputs):
    """Emits one schema version; v2 goes into its own namespace in every language (v2-generation.md).

    Constant fields are enforced only in v2, so the published v1 bindings stay byte-identical.
    """
    defs = definitions(ver)
    v2 = ver == 'v2'
    java_package = 'io.ololabs.toolgate.contracts' + ('.v2' if v2 else '')
    php_namespace = 'OloLabs\\ToolGate\\Contracts' + ('\\V2' if v2 else '')
    java_dir = 'packages/contracts/java/src/main/java/io/ololabs/toolgate/contracts/' + ('v2/' if v2 else '')
    php_dir = 'packages/contracts/php/src/' + ('V2/' if v2 else '')
    if v2:
        rust = ['//! Contract set v2 structural models. Validate canonical schemas at input boundaries.', '// ' + HEADER.replace('\n', '\n// '), 'use serde::{Deserialize, Serialize};', '',
                'macro_rules! const_field {', '    ($name:ident, $ty:ty, $value:expr) => {', '        fn $name<\'de, D: serde::Deserializer<\'de>>(deserializer: D) -> Result<$ty, D::Error> {',
                '            let value = <$ty as Deserialize>::deserialize(deserializer)?;', '            if value == $value {', '                Ok(value)', '            } else {',
                '                Err(serde::de::Error::custom(concat!(', '                    "expected constant ",', '                    stringify!($value)', '                )))', '            }', '        }', '    };', '}']
    else:
        rust = ['//! Shared structural contracts. Validate canonical schemas at input boundaries.', '// ' + HEADER.replace('\n', '\n// '), 'use serde::{Deserialize, Serialize};', '', '/// Contract set v2 models, side by side with v1 during migration.', 'pub mod v2;']
    ts = ['// ' + HEADER.replace('\n', '\n// ')]
    rust_consts = []
    for name, schema in sorted(defs.items()):
        java = ['// ' + HEADER.replace('\n', '\n// '), f'package {java_package};', '']
        php = ['<?php', '// ' + HEADER.replace('\n', '\n// '), 'declare(strict_types=1);', f'namespace {php_namespace};', '']
        if 'enum' in schema:
            values = schema['enum']
            java += [f'/** Canonical {name} wire values; unknown values must be rejected. */', f'public enum {name} {{']
            java += [f'    /** Canonical {value} value. */\n    ' + (f'@com.fasterxml.jackson.annotation.JsonProperty("{value}") ' if enum_member(value, 'java') != value else '') + enum_member(value, 'java') + (',' if i < len(values)-1 else '') for i, value in enumerate(values)]
            java += ['}']
            rust += [f'/// Canonical {name} wire values.', '#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]', f'pub enum {name} {{']
            rust += [f'    #[serde(rename = "{value}")]\n    {enum_member(value, "rust")},' for value in values]
            rust += ['}']
            ts += [f'export type {name} = ' + ' | '.join(json.dumps(v) for v in values) + ';']
            php += [f'/** Canonical {name} wire values. */', f'enum {name}: string {{'] + [f"    case {enum_member(v, 'php')} = '{v}';" for v in values] + ['}']
        elif schema.get('type') == 'object':
            props = schema['properties']
            required = set(schema['required'])
            description = schema['description']
            parameters = [f' * @param {java_ident(key)} canonical {key} value' for key in props]
            java += [f'/** {description}\n *\n' + '\n'.join(parameters) + '\n */', '@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)', f'public record {name}(']
            fields = []
            for key, prop in props.items():
                jt = field_type(prop, 'java', defs)
                if key not in required:
                    jt = {'long':'Long','boolean':'Boolean'}.get(jt, jt)
                annotation = f'@com.fasterxml.jackson.annotation.JsonProperty(value = "{key}", required = {str(key in required).lower()})'
                fields.append(f'    {annotation} {jt} {java_ident(key)}')
            java += [',\n'.join(fields), ') {', '    /** Reject absent required references and copy collections to retain value semantics.\n     *\n' + '\n'.join(f'     * @param {java_ident(key)} canonical {key} value' for key in props) + '\n     */', f'    public {name} {{']
            for key, prop in props.items():
                jt = field_type(prop, 'java', defs)
                if key in required and jt not in ('long','boolean'):
                    java += [f'        java.util.Objects.requireNonNull({java_ident(key)}, "{key}");']
                if jt.startswith('java.util.List'):
                    java += [f'        {java_ident(key)} = {java_ident(key)} == null ? null : java.util.List.copyOf({java_ident(key)});']
                if jt.startswith('java.util.Map'):
                    java += [f'        {java_ident(key)} = {java_ident(key)} == null ? null : java.util.Map.copyOf({java_ident(key)});']
                if v2 and 'const' in prop:
                    java += [f'        if ({java_ident(key)} != null && !java.util.Objects.equals({java_ident(key)}, {const_literal(prop["const"], "java")})) throw new IllegalArgumentException("{key} must be {prop["const"]}");']
            if name == 'ContractSet' and not v2:
                java += ['    }', '    /** Stable package identity. */', '    public static final String NAME = "olo-toolgate-contracts";', '    /** Canonical contract-set version. */', f'    public static final String VERSION = "{version}";', '    /** Embedded canonical schema inventory. */', '    public static final java.util.List<String> SCHEMA_FILES = java.util.List.of('+','.join('\"'+p.name+'\"' for p in sorted((CONTRACTS/'schemas/v1').glob('*.json')))+');', '    /** Current contract-set marker.\n     * @return the canonical identity and version\n     */', '    public static ContractSet current() { return new ContractSet(NAME, VERSION); }', '}']
            else:
                java += ['    }', '}']
            rust += [f'/// {description}'.rstrip(), '#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]', '#[serde(rename_all = "camelCase", deny_unknown_fields)]', f'pub struct {name} {{']
            ts += [f'/** {description} */', f'export interface {name} {{']
            php += [f'/** {description} */', f'final readonly class {name} implements \\JsonSerializable {{', '    public function __construct(']
            php_fields = []
            # PHP requires optional parameters after required parameters.
            ordered = sorted(props, key=lambda k: k not in required)
            for key, prop in props.items():
                rt = field_type(prop, 'rust', defs)
                optional = key not in required
                if optional:
                    rust += ['    #[serde(default, skip_serializing_if = "Option::is_none")]']
                    rt = f'Option<{rt}>'
                rust_key = snake(key)
                if rust_key in {'as','async','await','break','const','continue','crate','dyn','else','enum','extern','false','fn','for','if','impl','in','let','loop','match','mod','move','mut','pub','ref','return','self','Self','static','struct','super','trait','true','type','unsafe','use','where','while','yield'}:
                    rust_key = 'r#' + rust_key
                if v2 and 'const' in prop:
                    if optional:
                        raise ValueError(f'Optional const fields are not supported: {name}.{key}')
                    helper = f'const_{snake(name)}_{snake(key)}'
                    arguments = f'{helper}, {rt}, {const_literal(prop["const"], "rust")}'
                    # Laid out as rustfmt does: arguments wider than 60 columns go one per line.
                    rust_consts.append(f'const_field!({arguments});' if len(arguments) <= 60 else 'const_field!(\n    ' + arguments.replace(', ', ',\n    ') + '\n);')
                    rust += [f'    #[serde(deserialize_with = "{helper}")]']
                rust += [f'    pub {rust_key}: {rt},']
                tt = const_literal(prop['const'], 'ts') if v2 and 'const' in prop else field_type(prop, "ts", defs)
                ts += [f'  readonly {key}{"?" if optional else ""}: {tt};']
            for key in ordered:
                pt = field_type(props[key], 'php', defs)
                nullable = '?' if key not in required and pt != 'mixed' else ''
                php_fields.append(f'        public {nullable}{pt} ${key}' + (' = null' if key not in required else ''))
            php += [',\n'.join(php_fields), '    ) {}', '', '    /** Decode a structural model; canonical schema validation is also required. */', '    public static function fromArray(array $data): self {']
            keys = ', '.join(f"'{k}'" for k in props)
            req = ', '.join(f"'{k}'" for k in props if k in required)
            php += [f'        if (array_diff(array_keys($data), [{keys}]) || array_diff([{req}], array_keys($data))) {{', "            throw new \\InvalidArgumentException('Unknown or missing contract fields');", '        }']
            for key, prop in props.items():
                if v2 and 'const' in prop:
                    php += [f"        if ($data['{key}'] !== {const_literal(prop['const'], 'php')}) {{", f"            throw new \\InvalidArgumentException('{key} must be {prop['const']}');", '        }']
            php += ['        return new self(']
            php += [',\n'.join('            ' + (php_value(props[k], f"$data['{k}']", defs) if k in required else f"array_key_exists('{k}', $data) ? " + php_value(props[k], f"$data['{k}']", defs) + ' : null') for k in ordered), '        );', '    }', '', '    public function jsonSerialize(): object {', '        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);', '    }', '}']
            if name == 'ContractSet' and not v2:
                php[-1:-1] = ["    public const NAME = 'olo-toolgate-contracts';", f"    public const VERSION = '{version}';", '    /** Current canonical contract-set identity. */', '    public static function current(): self { return new self(self::NAME, self::VERSION); }']
            rust += ['}']
            ts += ['}']
        else:
            ts += [f'export type {name} = {field_type(schema, "ts", defs)};']
            if '$ref' in schema:
                # A named alias of another model; Java and PHP use the target type directly.
                rust += [f'/// {schema.get("description", name)}', f'pub type {name} = {field_type(schema, "rust", defs)};']
            continue
        outputs[java_dir + name + '.java'] = '\n'.join(java) + '\n'
        outputs[php_dir + name + '.php'] = '\n'.join(php) + '\n'
    if not v2:
        rust += ['impl ContractSet {', '    /// Current canonical contract set.', '    pub fn current() -> Self {', '        Self {', '            name: "olo-toolgate-contracts".into(),', f'            version: "{version}".into(),', '        }', '    }', '}']
    rust += rust_consts
    rust += ['/// Embedded canonical schemas for offline boundary validation.', 'pub const CANONICAL_SCHEMAS: &[(&str, &str)] = &[']
    for path in sorted((CONTRACTS/'schemas'/ver).glob('*.json')):
        schema = json.loads(path.read_text())
        outputs[f'packages/contracts/rust/schemas/{ver}/{path.name}'] = path.read_text(encoding='utf-8')
        rust += ['    (', f'        "{schema["$id"]}",', f'        include_str!("../schemas/{ver}/{path.name}"),', '    ),']
    rust += ['];']
    if not v2:
        ts += [f'export const CONTRACT_SET_VERSION = "{version}" as const;', 'export const CONTRACT_SET_NAME = "olo-toolgate-contracts" as const;']
    outputs['packages/contracts/rust/src/' + ('v2.rs' if v2 else 'lib.rs')] = '\n'.join(rust) + '\n'
    outputs['packages/contracts/typescript/src/' + ('v2/index.ts' if v2 else 'index.ts')] = '\n'.join(ts) + '\n'


def render():
    version = (CONTRACTS / 'VERSION').read_text().strip()
    outputs = {}
    for ver in VERSIONS:
        render_version(ver, version, outputs)
    for language in ('java','rust','typescript','php'):
        for document in ('LICENSE', 'NOTICE.md', 'RELEASE-NOTES.md'):
            outputs[f'packages/contracts/{language}/{document}'] = (ROOT/document).read_text(encoding='utf-8')
    schemas = sorted(p for ver in VERSIONS for p in (CONTRACTS/'schemas'/ver).glob('*.json'))
    manifest = {'version':version, 'schemaSha256':{str(p.relative_to(ROOT)).replace('\\','/'): source_sha256(p) for p in schemas}, 'generatorSha256':source_sha256(Path(__file__)), 'outputs': sorted(outputs)}
    outputs['packages/contracts/generated-manifest.json'] = json.dumps(manifest, indent=2) + '\n'
    return outputs


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    outputs = render()
    changed = []
    # Every generated directory is owned: a file in one without a canonical source is stale.
    owned = [CONTRACTS/'java/src/main/java/io/ololabs/toolgate/contracts', CONTRACTS/'java/src/main/java/io/ololabs/toolgate/contracts/v2',
             CONTRACTS/'php/src', CONTRACTS/'php/src/V2', CONTRACTS/'typescript/src', CONTRACTS/'typescript/src/v2', CONTRACTS/'rust/src']
    owned += [CONTRACTS/'rust/schemas'/ver for ver in VERSIONS]
    expected = set(outputs)
    for directory in owned:
        for path in directory.glob('*'):
            rel = path.relative_to(ROOT).as_posix()
            if path.is_file() and rel not in expected:
                changed.append(rel + ' (stale: no canonical source)')
                if not args.check:
                    path.unlink()
    for path, content in outputs.items():
        dest = ROOT / path
        if not dest.exists() or dest.read_bytes() != content.encode('utf-8'):
            changed.append(path)
            if not args.check:
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_text(content, encoding='utf-8', newline='\n')
    if args.check and changed:
        raise SystemExit('Generated code drift:\n' + '\n'.join(changed))
    print(f'Bindings {"verified" if args.check else "generated"}: {len(outputs)} files')


if __name__ == '__main__':
    main()
