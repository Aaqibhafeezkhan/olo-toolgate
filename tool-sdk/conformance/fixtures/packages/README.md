<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Package archive fixtures

## Purpose

These `.tgpkg` files exercise the archive rules of plan.md §7.6, used by `contract-descriptor/packages.json`. `valid.tgpkg` must be accepted at every hop, and every other file must be rejected. [`expected.json`](expected.json) lists each file's expected result, and the package digest of `valid.tgpkg`.

## Regenerating

```bash
python3 tool-sdk/conformance/fixtures/packages/generate.py          # rewrite the fixtures
python3 tool-sdk/conformance/fixtures/packages/generate.py --check  # verify the committed bytes
```

The generator writes entries in a fixed order, with fixed timestamps and no compression, so the output is byte-for-byte reproducible. Entry names retain their literal separators on every platform, including the deliberately invalid backslash in `backslash.tgpkg`. The exception is `zip-bomb-ratio.tgpkg`, which has to be deflated. Its compressed bytes can vary with the zlib version, so it is checked by its structure instead: a 16 MiB entry compressed more than 100:1.

`toolgate-package.json` in these files is a stub that carries only the artifact path and `artifactDigest`, named as in `PackagedArtifact` v2. It is just enough for the manifest-consistency rule and is not a complete `PackageManifest`.

## Files

| File | What is wrong |
|---|---|
| `valid` | Nothing |
| `zip-slip`, `absolute-path`, `backslash`, `drive-letter` | Unsafe entry paths |
| `duplicate-entry`, `case-collision`, `nfc-collision` | Entry names that collide exactly, after case folding, or after NFC normalisation |
| `symlink`, `encrypted-entry` | A symlink entry; an entry with the encryption flag set |
| `zip-bomb-ratio` | An entry over the compression-ratio ceiling |
| `data-outside-central-directory` | Bytes before the first local header |
| `unlisted-file`, `missing-listed-file` | The file set and `CHECKSUMS.sha256` disagree |
| `checksums-crlf`, `checksums-unsorted`, `checksums-uppercase-hex`, `checksums-comment`, `checksums-blank-line` | A non-canonical `CHECKSUMS.sha256` |
| `manifest-digest-mismatch` | A manifest artifact digest that differs from its `CHECKSUMS` entry |
