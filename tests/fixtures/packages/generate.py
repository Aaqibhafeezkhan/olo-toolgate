# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Generates the package regression fixtures (docs/implementation-prompt.md in olo-marketplace, PR 3 step 1).

Usage: python3 generate.py          write every fixture
       python3 generate.py --check  fail if any committed fixture differs from a fresh build

Each fixture starts from a real manifest in templates/ (the examples.notify and examples.csv packages as the
Java packager wrote them), with a small stand-in jar so the files stay small. Entries are stored, in a fixed order,
with fixed timestamps, so the bytes are reproducible. baseline.json is not written here: it records what Control
decides for each fixture, and PackageArchiveBaselineTest writes and checks it.
"""
import copy
import hashlib
import io
import json
import sys
import unicodedata
import warnings
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
OUT = HERE / 'cases'
EPOCH = (1980, 1, 1, 0, 0, 0)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def canonical(value):
    """RFC 8785 with NFC strings, for the values the templates hold (tool-sdk/spec/descriptor §3)."""
    if isinstance(value, dict):
        items = sorted(((unicodedata.normalize('NFC', k), v) for k, v in value.items()), key=lambda kv: kv[0].encode('utf-16-be'))
        return '{' + ','.join(canonical(k) + ':' + canonical(v) for k, v in items) + '}'
    if isinstance(value, list):
        return '[' + ','.join(canonical(v) for v in value) + ']'
    if isinstance(value, str):
        return json.dumps(unicodedata.normalize('NFC', value), ensure_ascii=False)
    if isinstance(value, bool):
        return 'true' if value else 'false'
    if value is None:
        return 'null'
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float) and value in (0.5, 1e21):
        return {0.5: '0.5', 1e21: '1e+21'}[value]
    raise ValueError(f'no canonical form for {value!r}')


def info(name, compress=zipfile.ZIP_STORED, mode=0o100644):
    zi = zipfile.ZipInfo(name, date_time=EPOCH)
    zi.filename = name
    zi.compress_type = compress
    zi.create_system = 3
    zi.external_attr = mode << 16
    return zi


def build(entries):
    out = io.BytesIO()
    with warnings.catch_warnings():
        warnings.simplefilter('ignore')
        with zipfile.ZipFile(out, 'w') as zf:
            for zi, data in entries:
                zf.writestr(zi, data)
    return out.getvalue()


# A stand-in for the tool's fat jar: a real, tiny jar. Validation checks its digest and size, never its contents.
JAR = build([(info('META-INF/MANIFEST.MF'), b'Manifest-Version: 1.0\r\nCreated-By: toolgate-fixtures\r\n\r\n')])


def template(name):
    return json.loads((HERE / 'templates' / f'{name}.json').read_text(encoding='utf-8'))


def set_digest(manifest):
    pairs = sorted((t['descriptor']['toolId'], t['toolDescriptorDigest']) for t in manifest['tools'])
    pairs.sort(key=lambda p: p[0].encode('utf-16-be'))
    return 'sha256:' + sha(canonical([{'toolId': i, 'toolDescriptorDigest': d} for i, d in pairs]).encode())


def seal(manifest, files=None, jar=JAR, refresh=True):
    """The package files for a manifest, with every digest the packager computes filled in."""
    manifest = copy.deepcopy(manifest)
    files = dict(files or {})
    for artifact in manifest['artifacts']:
        if refresh:
            artifact['artifactDigest'] = 'sha256:' + sha(jar)
            artifact['sizeBytes'] = len(jar)
        files.setdefault(artifact['path'], jar)
    for tool in manifest['tools']:
        body = canonical(tool['descriptor']).encode()
        if refresh:
            tool['toolDescriptorDigest'] = 'sha256:' + sha(body)
        files.setdefault('tools/' + tool['descriptor']['toolId'].split('/', 1)[-1] + '.json', body)
    if refresh:
        manifest['descriptorSetDigest'] = set_digest(manifest)
    return manifest, files


def manifest_bytes(manifest):
    return (json.dumps(manifest, indent=2, ensure_ascii=False) + '\n').encode()


def checksums(files):
    return ''.join(f'{sha(d)}  {n}\n' for n, d in sorted(files.items(), key=lambda kv: kv[0].encode())).encode()


def package(manifest=None, files=None, raw_manifest=None, checksum_bytes=None, extra=(), compress=zipfile.ZIP_STORED):
    files = dict(files or {})
    if manifest is not None or raw_manifest is not None:
        files['toolgate-package.json'] = raw_manifest if raw_manifest is not None else manifest_bytes(manifest)
    signatures = {n: d for n, d in files.items() if n.startswith('signatures/')}
    listed = {n: d for n, d in files.items() if n not in signatures}
    body = checksum_bytes if checksum_bytes is not None else checksums(listed)
    entries = [(info(n, compress), d) for n, d in sorted(listed.items(), key=lambda kv: kv[0].encode())]
    entries.append((info('CHECKSUMS.sha256'), body))
    entries.extend((info(n), d) for n, d in sorted(signatures.items()))
    entries.extend(extra)
    return build(entries)


def with_descriptor_change(manifest, change, tool=0):
    manifest = copy.deepcopy(manifest)
    change(manifest['tools'][tool]['descriptor'])
    return manifest


def nested(depth):
    return '{"a":' * depth + '1' + '}' * depth


def fixtures():
    notify, csv = template('examples.notify'), template('examples.csv')
    out = {}

    # Accepted today.
    m, f = seal(notify); out['valid-notify'] = package(m, f)
    m, f = seal(csv); out['valid-csv-two-tools'] = package(m, f)
    m, f = seal(notify); out['valid-deflated'] = package(m, f, compress=zipfile.ZIP_DEFLATED)
    m, f = seal(with_descriptor_change(notify, lambda d: d.update(description='Envoie une notification à l’équipe — café \U0001f514'))); out['valid-unicode-descriptor'] = package(m, f)
    m, f = seal(with_descriptor_change(notify, lambda d: d['inputSchema']['properties']['title'].update(maxLength=200, multipleOf=0.5))); out['valid-fractional-number'] = package(m, f)
    m, f = seal(notify); out['valid-signatures-entry'] = package(m, {**f, 'signatures/marketplace.sig': b'not listed in CHECKSUMS\n'})
    m, f = seal(notify); m2 = copy.deepcopy(m); m2['unknownField'] = True
    out['schema-unknown-field'] = package(m2, f)  # Control has no schema check; the CLI checks PackageManifest v2.
    m, f = seal(notify); m2 = copy.deepcopy(m); m2['schemaVersion'] = '2'
    out['schema-version-string'] = package(m2, f)
    m, f = seal(notify); m2 = copy.deepcopy(m); del m2['version']; m2['version'] = 'not-semver'
    out['version-not-semver'] = package(m2, f)

    # Manifest JSON.
    m, f = seal(notify)
    out['manifest-not-json'] = package(files=f, raw_manifest=b'{"id": "examples.notify",\n')
    out['manifest-duplicate-key'] = package(files=f, raw_manifest=manifest_bytes(m).replace(b'{\n  "artifacts"', b'{\n  "id": "examples.other",\n  "artifacts"', 1))
    out['manifest-trailing-tokens'] = package(files=f, raw_manifest=manifest_bytes(m) + b'{}\n')
    out['manifest-bom'] = package(files=f, raw_manifest=b'\xef\xbb\xbf' + manifest_bytes(m))
    out['manifest-too-deep'] = package(files=f, raw_manifest=manifest_bytes(m)[:-2] + b',\n  "deep": ' + nested(33).encode() + b'\n}\n')
    out['manifest-array'] = package(files=f, raw_manifest=b'[]\n')
    out['manifest-invalid-utf8'] = package(files=f, raw_manifest=manifest_bytes(m).replace(b'"ToolGate Examples"', b'"ToolGate \xff Examples"'))
    out['manifest-comment'] = package(files=f, raw_manifest=b'// packager output\n' + manifest_bytes(m))
    out['missing-manifest'] = package(files=f)
    out['missing-checksums'] = build([(info(n), d) for n, d in sorted({**f, 'toolgate-package.json': manifest_bytes(m)}.items())])

    # Manifest fields.
    def variant(name, change, template_manifest=notify, refresh_after=False):
        m, f = seal(template_manifest)
        m = copy.deepcopy(m)
        change(m, f)
        if refresh_after:
            m, f = seal(m, {k: v for k, v in f.items() if not k.startswith('tools/')})
        out[name] = package(m, f)

    variant('schema-version-1', lambda m, f: m.update(schemaVersion=1))
    variant('schema-version-float', lambda m, f: m.update(schemaVersion=2.0))
    variant('id-uppercase', lambda m, f: m.update(id='Examples.Notify'), refresh_after=False)
    variant('id-not-text', lambda m, f: m.update(id=7))
    variant('version-missing', lambda m, f: m.pop('version'))
    variant('artifact-digest-mismatch', lambda m, f: m['artifacts'][0].update(artifactDigest='sha256:' + '0' * 64))
    variant('artifact-size-mismatch', lambda m, f: m['artifacts'][0].update(sizeBytes=1))
    variant('artifact-outside-artifacts', lambda m, f: (m['artifacts'][0].update(path='lib/tool.jar'), f.update({'lib/tool.jar': f.pop('artifacts/tool.jar')})))
    variant('artifact-duplicate-id', lambda m, f: (m['artifacts'].append(dict(m['artifacts'][0], path='artifacts/copy.jar')), f.update({'artifacts/copy.jar': JAR})))
    variant('artifact-undeclared-runtime', lambda m, f: m['artifacts'][0].update(runtimeId='python'))
    variant('artifact-without-id', lambda m, f: m['artifacts'][0].pop('artifactId'))
    variant('tool-unknown-artifact', lambda m, f: m['tools'][0].update(artifactIds=['missing']))
    variant('tool-undeclared-runtime', lambda m, f: m['tools'][0].update(runtimeId='python'))
    variant('java-tool-two-jars', lambda m, f: (m['artifacts'].append(dict(m['artifacts'][0], artifactId='extra', path='artifacts/extra.jar')),
                                                f.update({'artifacts/extra.jar': JAR}), m['tools'][0].update(artifactIds=['tool', 'extra'])))
    variant('java-tool-not-a-jar', lambda m, f: (m['artifacts'][0].update(mediaType='application/zip')))
    variant('java-tool-no-artifact', lambda m, f: m['tools'][0].update(artifactIds=[]))
    variant('tool-digest-mismatch', lambda m, f: m['tools'][0].update(toolDescriptorDigest='sha256:' + '0' * 64))
    variant('tool-file-not-canonical', lambda m, f: f.update({'tools/send.json': f['tools/send.json'] + b'\n'}))
    variant('tool-file-missing', lambda m, f: f.pop('tools/send.json'))
    variant('tools-extra-file', lambda m, f: f.update({'tools/other.json': b'{}'}))
    variant('tool-outside-package', lambda m, f: m['tools'][0]['descriptor'].update(toolId='examples.other/send'), refresh_after=True)
    variant('tool-id-no-local-name', lambda m, f: m['tools'][0]['descriptor'].update(toolId='examples.notify'), refresh_after=True)
    variant('tool-generation-mismatch', lambda m, f: m['tools'][0]['descriptor'].update(descriptorGeneration=2), refresh_after=True)
    variant('tools-over-max', lambda m, f: m['limits'].update(maxTools=1), template_manifest=csv)
    variant('tools-not-array', lambda m, f: m.update(tools={}))
    variant('descriptor-over-max-bytes', lambda m, f: m['limits'].update(maxDescriptorBytes=100))
    variant('duplicate-tool', lambda m, f: m['tools'].append(copy.deepcopy(m['tools'][0])))
    variant('set-digest-mismatch', lambda m, f: m.update(descriptorSetDigest='sha256:' + '0' * 64))

    # Descriptors Canonical cannot write: keys that collide in NFC, and a number outside the double range.
    m, f = seal(with_descriptor_change(notify, lambda d: d['inputSchema']['properties'].update({'caf\u00e9': {'type': 'string'}, 'cafe\u0301': {'type': 'string'}})))
    out['descriptor-nfc-key-collision'] = package(m, f)
    m, f = seal(with_descriptor_change(notify, lambda d: d['inputSchema']['properties']['title'].update(maxLength=123456789)))
    out['descriptor-number-overflow'] = package(files=f, raw_manifest=manifest_bytes(m).replace(b'123456789', b'1e400'))

    # Archive structure, from a valid package.
    m, f = seal(notify)
    good = package(m, f)
    listed = checksums({**f, 'toolgate-package.json': manifest_bytes(m)})
    out['archive-comment'] = good[:-2] + b'\x04\x00' + b'olo!'
    out['archive-trailing-bytes'] = good + b'\0'
    out['entry-directory'] = package(m, f, extra=[(info('tools/'), b'')])
    out['entry-symlink'] = package(m, f, extra=[(info('artifacts/link', mode=0o120777), b'tool.jar')])
    out['entry-traversal'] = package(m, {**f, '../escape.txt': b'x'})
    out['checksums-uppercase'] = package(m, f, checksum_bytes=listed.upper())
    out['checksums-missing-final-newline'] = package(m, f, checksum_bytes=listed[:-1])
    out['checksums-lists-itself'] = package(m, f, checksum_bytes=checksums({**f, 'toolgate-package.json': manifest_bytes(m), 'CHECKSUMS.sha256': b''}))
    out['truncated'] = good[: len(good) // 2]
    out['empty'] = b''
    return out


def main(argv):
    out = fixtures()
    if '--check' in argv:
        bad = []
        for name, data in out.items():
            path = OUT / f'{name}.tgpkg'
            if not path.is_file():
                bad.append(f'missing {path.name}')
            elif name == 'valid-deflated':
                with zipfile.ZipFile(path) as zf:  # deflated bytes depend on the zlib version
                    if zf.read('CHECKSUMS.sha256') != zipfile.ZipFile(io.BytesIO(data)).read('CHECKSUMS.sha256'):
                        bad.append(f'{path.name} differs from a fresh build')
            elif path.read_bytes() != data:
                bad.append(f'{path.name} differs from a fresh build')
        bad.extend(f'unexpected {p.name}' for p in sorted(OUT.glob('*.tgpkg')) if p.stem not in out)
        if bad:
            raise SystemExit('\n'.join(bad))
        print(f'{len(out)} package fixtures match the generator')
        return
    OUT.mkdir(exist_ok=True)
    for name, data in out.items():
        (OUT / f'{name}.tgpkg').write_bytes(data)
    print(f'wrote {len(out)} package fixtures to {OUT.relative_to(HERE)}/')


if __name__ == '__main__':
    main(sys.argv[1:])
