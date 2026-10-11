<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# toolgate CLI

## Purpose

`toolgate` is the single packager behind every Tool SDK (plan.md §4). Language SDKs turn annotations and types into semantic descriptors; this crate turns descriptors into canonical bytes and digests, and builds and checks `.tgpkg` packages. It works offline: the canonical schemas come from the contracts crate.

This is milestone M0 part 1. `init`, `dev`, `sign`, `upload`, `publish`, `import-openapi` and `add-mcp` arrive in later parts.

## Commands

| Command | Does |
|---|---|
| `toolgate pack <staging-dir> [-o file.tgpkg]` | Builds a package and prints its digests (below) |
| `toolgate validate <file.tgpkg>` | The archive rules, then the manifest against `PackageManifest` v2, every `toolDescriptorDigest`, `descriptorSetDigest`, the `tools/` files, artifact and runtime references, and the ceilings |
| `toolgate verify <file.tgpkg>` | The archive rules only, as every hop runs them (plan.md §7.6); prints `packageDigest` |
| `toolgate digest <descriptor.json>...` | `toolDescriptorDigest` of each descriptor, and their `descriptorSetDigest` |
| `toolgate canonical <descriptor.json>` | The canonical bytes (NFC, then RFC 8785) |
| `toolgate equivalent <a.json> <b.json>` | Compares two descriptors under rewrite list 1; exits 1 when they differ |

Exit codes: 0 success, 1 rejected or invalid input, 2 usage.

## Packing

The staging directory holds `toolgate-package.json` and the artifacts it lists. `pack`:

1. computes `artifactDigest` and `sizeBytes` for each artifact, `toolDescriptorDigest` for each tool and `descriptorSetDigest`. A value already present must match, so a stale build fails instead of being overwritten;
2. writes `tools/<tool local name>.json` with each canonical descriptor, and the manifest as canonical JSON plus a newline;
3. copies `marketplace/listing.json`, `marketplace/README.md`, `marketplace/CHANGELOG.md`, `marketplace/icon.png`, `tests/self-tests.json` and `sbom/cyclonedx.json` when present. Nothing else in the directory is packaged;
4. writes `CHECKSUMS.sha256` and a deterministic zip: entries sorted bytewise, stored, dated 1980-01-01, mode 0644;
5. validates its own output.

The same inputs give the same bytes in any directory or machine.

## Archive rules

`verify` reads the central directory and checks it before inflating anything. It rejects:
- paths that are absolute, contain `..`, `.`, empty segments, a backslash or a colon, or are not NFC;
- names that are equal exactly, after NFC, or after case folding;
- symlinks, directories and other non-regular entries, and encrypted entries;
- zip64, multi-disk archives, and methods other than stored and deflate;
- bytes before, between or after the entries, and an archive comment;
- more than 4096 entries, an entry over 512 MiB, more than 1 GiB in total, or an entry over 1 MiB with a compression ratio above 100:1;
- a `CHECKSUMS.sha256` that is not canonical, a file it does not list (outside `signatures/`), a listed file that is missing, and a mismatched digest;
- a manifest artifact whose `artifactDigest` or `sizeBytes` differs from the packaged file.

`packageDigest` is `sha256:` plus the SHA-256 of the exact `CHECKSUMS.sha256` bytes, so adding signatures never changes it.

## Conformance

`cargo test -p toolgate-cli` runs the packager's cases from [contract-descriptor](../conformance/contract-descriptor/): every package archive fixture, the descriptor vectors, the equivalence vectors, the package-digest, artifact-digest, reproducibility, excludes-deployment and includes-semantics cases, and the v2 schema fixtures. [tests/conformance.rs](tests/conformance.rs) lists which test runs which case. A case that also targets an SDK or a platform component stays `authored` until those runners pass it too.
