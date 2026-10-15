# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Generates the package archive fixtures for contract-descriptor/packages.json (platform-requirements.md §7.6).

Usage: python3 generate.py          write every fixture and expected.json
       python3 generate.py --check  fail if any committed fixture differs from a fresh build

Entries are written in a fixed order with fixed timestamps and ZIP_STORED, so the bytes do not
depend on the zlib version. The one deflated fixture (zip-bomb-ratio) is checked by structure.
"""
import hashlib
import io
import json
import sys
import unicodedata
import warnings
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
EPOCH = (1980, 1, 1, 0, 0, 0)
ARTIFACT = b'\x7fTOOLGATE-FIXTURE-ARTIFACT\n' * 8
TOOL = b'{"toolId":"acme.orders/orders.lookup","title":"Look up an order"}\n'
DEFLATED = {'zip-bomb-ratio'}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def manifest(artifact_digest):
    return (json.dumps({
        '$comment': 'Archive-rule fixture only; not a complete PackageManifest.',
        'id': 'acme.orders', 'version': '1.0.0',
        'artifacts': [{'path': 'artifacts/app.bin', 'artifactDigest': 'sha256:' + artifact_digest}],
    }, sort_keys=True, separators=(',', ':')) + '\n').encode()


def checksums(files):
    lines = [f'{sha(data)}  {name}\n' for name, data in sorted(files.items(), key=lambda kv: kv[0].encode())]
    return ''.join(lines).encode()


def base_files():
    return {'artifacts/app.bin': ARTIFACT, 'toolgate-package.json': manifest(sha(ARTIFACT)), 'tools/orders.lookup.json': TOOL}


def info(name, compress=zipfile.ZIP_STORED):
    zi = zipfile.ZipInfo(name, date_time=EPOCH)
    # ZipInfo normalizes host separators; preserve intentionally unsafe fixture paths.
    zi.filename = name
    zi.compress_type = compress
    zi.create_system = 3
    zi.external_attr = 0o100644 << 16
    return zi


def build(entries):
    """entries: list of (ZipInfo, bytes) written in order."""
    out = io.BytesIO()
    with warnings.catch_warnings():
        warnings.simplefilter('ignore')
        with zipfile.ZipFile(out, 'w') as zf:
            for zi, data in entries:
                zf.writestr(zi, data)
    return out.getvalue()


def package(files, checksum_bytes=None, extra=()):
    files = dict(files)
    body = checksum_bytes if checksum_bytes is not None else checksums(files)
    entries = [(info(n), d) for n, d in sorted(files.items(), key=lambda kv: kv[0].encode())]
    entries.append((info('CHECKSUMS.sha256'), body))
    entries.extend(extra)
    return build(entries)


def fixtures():
    f = base_files()
    good = checksums(f)
    out = {'valid': package(f)}
    out['zip-slip'] = package({**f, '../evil.txt': b'x'})
    out['absolute-path'] = package({**f, '/etc/evil.txt': b'x'})
    out['backslash'] = package({**f, 'tools\\evil.json': b'x'})
    out['drive-letter'] = package({**f, 'C:/evil.txt': b'x'})
    out['duplicate-entry'] = package(f, extra=[(info('tools/orders.lookup.json'), b'{"other":true}\n')])
    out['case-collision'] = package({**f, 'tools/Orders.Lookup.json': TOOL})
    nfc = unicodedata.normalize('NFC', 'tools/caf\u00e9.json')
    nfd = unicodedata.normalize('NFD', nfc)
    out['nfc-collision'] = package({**f, nfc: b'1', nfd: b'2'})
    link = info('tools/link.json')
    link.external_attr = 0o120777 << 16
    out['symlink'] = package(f, extra=[(link, b'../toolgate-package.json')])
    out['encrypted-entry'] = set_encrypted(package(f, extra=[(info('tools/secret.json'), b'not really encrypted')]), b'tools/secret.json')
    out['zip-bomb-ratio'] = _deflate_bomb(f, b'\0' * (16 * 1024 * 1024))
    out['data-outside-central-directory'] = b'PREPENDED-BYTES\n' + out['valid']
    out['unlisted-file'] = package({**f, 'tools/extra.json': b'{}\n'}, checksum_bytes=good)
    missing = dict(f); missing.pop('tools/orders.lookup.json')
    out['missing-listed-file'] = package(missing, checksum_bytes=good)
    out['checksums-crlf'] = package(f, checksum_bytes=good.replace(b'\n', b'\r\n'))
    out['checksums-unsorted'] = package(f, checksum_bytes=b''.join(reversed(good.splitlines(keepends=True))))
    first, rest = good.split(b'  ', 1)
    out['checksums-uppercase-hex'] = package(f, checksum_bytes=first.upper() + b'  ' + rest)
    out['checksums-comment'] = package(f, checksum_bytes=b'# generated\n' + good)
    out['checksums-blank-line'] = package(f, checksum_bytes=good + b'\n')
    wrong = {**f, 'toolgate-package.json': manifest('0' * 64)}
    out['manifest-digest-mismatch'] = package(wrong)
    return out


def set_encrypted(data, name):
    """Sets general-purpose flag bit 0 (encrypted) on one entry's local and central headers."""
    buf = bytearray(data)
    for signature, flag_at, name_len_at, header_len in ((b'PK\x03\x04', 6, 26, 30), (b'PK\x01\x02', 8, 28, 46)):
        start = 0
        while (pos := buf.find(signature, start)) >= 0:
            length = int.from_bytes(buf[pos + name_len_at:pos + name_len_at + 2], 'little')
            if bytes(buf[pos + header_len:pos + header_len + length]) == name:
                buf[pos + flag_at] |= 0x1
            start = pos + 4
    return bytes(buf)


def _deflate_bomb(f, bomb):
    files = {**f, 'artifacts/bomb.bin': bomb}
    entries = []
    for n, d in sorted(files.items()):
        entries.append((info(n, zipfile.ZIP_DEFLATED if n == 'artifacts/bomb.bin' else zipfile.ZIP_STORED), d))
    entries.append((info('CHECKSUMS.sha256'), checksums(files)))
    return build(entries)


def expected(out):
    return {
        '$comment': 'Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0. Generated by generate.py.',
        'validPackageDigest': 'sha256:' + sha(checksums(base_files())),
        'fixtures': {name: ('accept' if name == 'valid' else 'reject') for name in sorted(out)},
    }


def bomb_ok(data):
    with zipfile.ZipFile(io.BytesIO(data)) as zf:
        zi = zf.getinfo('artifacts/bomb.bin')
        return zi.compress_type == zipfile.ZIP_DEFLATED and zi.file_size == 16 * 1024 * 1024 and zi.file_size / zi.compress_size > 100


def main(argv):
    out = fixtures()
    exp = (json.dumps(expected(out), indent=2) + '\n').encode()
    if '--check' in argv:
        bad = []
        for name, data in out.items():
            path = HERE / f'{name}.tgpkg'
            if not path.is_file():
                bad.append(f'missing {path.name}')
            elif name in DEFLATED:
                if not bomb_ok(path.read_bytes()):
                    bad.append(f'{path.name} has the wrong structure')
            elif path.read_bytes() != data:
                bad.append(f'{path.name} differs from a fresh build')
        if (HERE / 'expected.json').read_bytes() != exp:
            bad.append('expected.json differs from a fresh build')
        extra = {p.stem for p in HERE.glob('*.tgpkg')} - set(out)
        bad.extend(f'unexpected {name}.tgpkg' for name in sorted(extra))
        if bad:
            raise SystemExit('\n'.join(bad))
        print(f'{len(out)} package fixtures match the generator')
        return
    for name, data in out.items():
        (HERE / f'{name}.tgpkg').write_bytes(data)
    (HERE / 'expected.json').write_bytes(exp)
    print(f'wrote {len(out)} package fixtures')


if __name__ == '__main__':
    main(sys.argv[1:])
