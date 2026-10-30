<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# toolgate CLI

## Purpose

`toolgate` is the single packager behind every Tool SDK (platform-requirements.md §4), and the one command line a tool author needs in any language. Language SDKs turn annotations and types into semantic descriptors; this crate turns descriptors into canonical bytes and digests, builds and checks `.tgpkg` packages, creates tool projects from starters and drives their builds. It works offline: the canonical schemas come from the contracts crate and the starters are embedded.

`sign`, `upload`, `publish`, `import-openapi` and `add-mcp` arrive in later parts.

## Project commands

| Command | Does |
|---|---|
| `toolgate init <name> [--lang java] [--build gradle\|maven]` | Writes a new tool project from a [starter](starters/README.md) into `./<name>` (or `--dir`). `--id` sets the package id (default `example.<name>`), `--publisher` the publisher, `--sdk-version` the SDK version (default this CLI's), and `--sdk-repo` a repository URL or directory holding the SDK (default the local Maven repository) |
| `toolgate dev [dir]` | Builds the tools and serves them as an MCP server over stdio in development mode, with `toolgate.dev.json`. `--http host:port` serves Streamable HTTP on a loopback address instead |
| `toolgate dev --digests` | Starts the server, asks `server/discover`, and prints each `toolDescriptorDigest` and the `descriptorSetDigest` (runtime-modes.md §3) |
| `toolgate check [dir]` | Compiles, runs the unit tests and every `@Example` in test mode, and fails if `toolgate.dev.json` is tracked by git |
| `toolgate pack [dir] [-o file.tgpkg]` | Builds the package with the project's build, then validates it and prints its digests |
| `toolgate guide` | Prints the [community guide](../../TOOL-SDK-GUIDE.md) to building tools with the SDK |

`dir` defaults to the current directory. The CLI never compiles code: an adapter per language and build tool ([src/project.rs](src/project.rs)) finds the project by its marker file (`settings.gradle(.kts)`/`build.gradle(.kts)`, `pom.xml`) and runs that build's own tasks (`toolgateDev`, `check`, `toolgatePackage`; `toolgate:dev`, `test`, `package`), using `./gradlew` or `./mvnw` when the project has one. `--build gradle|maven` chooses when a directory has both, and anything after `--` goes to the build tool. The build calls back into this binary to pack (`TOOLGATE_CLI`).

## Package commands

| Command | Does |
|---|---|
| `toolgate pack <staging-dir> [-o file.tgpkg]` | Builds a package from a directory holding `toolgate-package.json`, and prints its digests (below) |
| `toolgate validate <file.tgpkg>` | The archive rules, then the manifest against `PackageManifest` v2, every `toolDescriptorDigest`, `descriptorSetDigest`, the `tools/` files, artifact and runtime references, and the ceilings |
| `toolgate verify <file.tgpkg>` | The archive rules only, as every hop runs them (platform-requirements.md §7.6); prints `packageDigest` |
| `toolgate schema <file.tgpkg \| staging-dir> [--annotations file] [-o file]` | Writes the [tool schema](#tool-schema) (default `<file>.tool-schema.json`, or `tool-schema.json` in the staging directory) |
| `toolgate digest <descriptor.json>...` | `toolDescriptorDigest` of each descriptor, and their `descriptorSetDigest` |
| `toolgate canonical <descriptor.json>` | The canonical bytes (NFC, then RFC 8785) |
| `toolgate equivalent <a.json> <b.json>` | Compares two descriptors under rewrite list 1; exits 1 when they differ |

`toolgate validate <dir>` on a project directory runs `check`.

Exit codes: 0 success, 1 rejected or invalid input (or a failed build), 2 usage.

## Packing

The staging directory holds `toolgate-package.json` and the artifacts it lists. `pack`:

1. computes `artifactDigest` and `sizeBytes` for each artifact, `toolDescriptorDigest` for each tool and `descriptorSetDigest`. A value already present must match, so a stale build fails instead of being overwritten;
2. writes `tools/<tool local name>.json` with each canonical descriptor, and the manifest as canonical JSON plus a newline;
3. copies `marketplace/listing.json`, `marketplace/README.md`, `marketplace/CHANGELOG.md`, `marketplace/icon.png`, `tests/self-tests.json` and `sbom/cyclonedx.json` when present. Nothing else in the directory is packaged;
4. writes `CHECKSUMS.sha256` and a deterministic zip: entries sorted bytewise, stored, dated 1980-01-01, mode 0644;
5. validates its own output.

The same inputs give the same bytes in any directory or machine.

## Tool schema

After packing, `pack` writes `<package>.tool-schema.json` beside the `.tgpkg` ([src/tool_schema.rs](src/tool_schema.rs)). It holds what a training pipeline needs to teach a model to call the tools: per tool, `toolId`, `name`, `mcpName`, `kind`, `title`, `description`, `useWhen`, `effect`, a flat `parameters` list (`name`, `type`, `description`, `required`, `default`, `enum`, `sensitive`, `constraints`), the full `inputSchema` and `outputSchema`, and `annotations`. Annotations come from `training/annotations.json` in the project (or staging) directory, keyed by tool local name; a key that names no tool is refused. The file is not packaged, so no digest changes.

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

## Starters and adding a language

[starters/README.md](starters/README.md) describes the starter format. A new language adds a starter, its template variables and one adapter; everything else (digests, packing, validation, test mode's exchange format) is already shared.

## Conformance

`cargo test -p toolgate-cli` runs the packager's cases from [contract-descriptor](../conformance/contract-descriptor/): every package archive fixture, the descriptor vectors, the equivalence vectors, the package-digest, artifact-digest, reproducibility, excludes-deployment and includes-semantics cases, and the v2 schema fixtures. [tests/conformance.rs](tests/conformance.rs) lists which test runs which case. [tests/project.rs](tests/project.rs) covers `init` and the adapters; the Java end-to-end run ([java-sdk/e2e/run.sh](../java-sdk/e2e/run.sh)) builds what `init` writes with Gradle and Maven. A case that also targets an SDK or a platform component stays `authored` until those runners pass it too.
