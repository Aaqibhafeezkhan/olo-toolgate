<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Marketplace helper

Validates and signs `.tgpkg` packages for the OLO Orgs Marketplace. Drupal is its only caller, over the internal network. The API is in [../docs/api.md](../docs/api.md) §3.

| Endpoint | What it does |
|---|---|
| `POST /inspect` | Validates the package, extracts the listing, tools, typed settings and compatibility block, and diffs it against the previous version. Never signs. |
| `POST /sign` | Re-reads and re-validates the bytes, checks the digest, the publisher prefix and the reserved prefixes, then signs a release record. |
| `GET /keys` | The key set (current and retired) plus the ToolGate revision the image was built from |
| `GET /health` | `{"status":"ok"}`; the only route without the bearer token |

## How it validates

1. Reads the bytes by `fileRef` from object storage, at most `MARKETPLACE_MAX_PACKAGE_MB`.
2. Validates them in memory with ToolGate's `backend-libs/package-archive`, the same code Control runs on upload (design §6, "Validators"). This is the authoritative validator. The helper compiles its sources from `TOOLGATE_DIR`, so it still needs no repository.
3. Runs the unchanged ToolGate CLI, `toolgate validate <file>`, as a reject-only extra check. It only ever passes a regular file it wrote itself to a private temporary directory; given a directory, the CLI would compile and run the project's tests. Where both accept, the digests must match. The CLI also checks the manifest against `PackageManifest` v2, so it rejects a few packages Control's rules accept; `CliValidator.REJECT_ONLY` lists them and `DifferentialTest` keeps that list exact:
   - an unknown manifest field;
   - `schemaVersion` written as the string `"2"`;
   - a `version` that is not SemVer;
   - a manifest that is not UTF-8.
4. Enforces `MARKETPLACE_MAX_EXPANDED_MB` on the extracted files.
5. Applies the Marketplace's own rules: SDK packages with Java server tools only, a publisher prefix in the id, a well-formed compatibility block, and the SemVer check against the previous version.

Nothing in a package is ever executed. Each validation stages one package in `/tmp` for the CLI, so size that tmpfs for `MARKETPLACE_HELPER_THREADS` × `MARKETPLACE_MAX_PACKAGE_MB` (Compose and Helm do).

## Dependencies

Only the JDK. The JSON reader, RFC 8785 canonical form, Ed25519 signing (JDK `Ed25519`), S3 SigV4 reads and the HTTP server (`com.sun.net.httpserver`) are all in this source tree. The Gradle build declares no repositories, the image compiles with plain `javac`, and the tests use their own small runner (`TestMain`), so nothing comes from Maven Central.

## Running

```bash
scripts/init-signing-key.sh                     # once; refuses to replace an existing key
MARKETPLACE_ID=olo-labs-marketplace MARKETPLACE_SIGNING_KEY_ID=olo-marketplace-2026-1 \
MARKETPLACE_HELPER_TOKEN=<random> MARKETPLACE_SIGNING_KEY_FILE=deploy/secrets/marketplace_signing_key \
TOOLGATE_CLI=<path to toolgate> java -jar helper/build/libs/marketplace-helper.jar
```

The helper refuses to start without a token of at least 16 characters, a readable Ed25519 key or the CLI. In the container it runs as a non-root user and works with a read-only root filesystem plus a tmpfs at `/tmp`.

## Settings

| Variable | Default | Meaning |
|---|---|---|
| `MARKETPLACE_ID` | required | Signed into every release record as `marketplace` |
| `MARKETPLACE_HELPER_TOKEN` | required | Bearer token Drupal sends; or `MARKETPLACE_HELPER_TOKEN_FILE`, a file holding it (the S3 keys take `_FILE` too) |
| `MARKETPLACE_SIGNING_KEY_FILE` | `/run/secrets/marketplace_signing_key` | PKCS#8 PEM Ed25519 key from `init-signing-key.sh` |
| `MARKETPLACE_SIGNING_KEY_ID` | required | `keyId` of that key |
| `MARKETPLACE_RETIRED_KEYS` | empty | `keyId:base64PublicKey,…` still listed after a rotation |
| `MARKETPLACE_MAX_PACKAGE_MB` / `MARKETPLACE_MAX_EXPANDED_MB` | 50 / 200 | Size limits; keep the first below Control's 64 MiB |
| `MARKETPLACE_VALIDATION_TIMEOUT_SECONDS` | 30 | Per CLI run |
| `MARKETPLACE_HELPER_THREADS` | 4 | Concurrent validations; more get `503` |
| `MARKETPLACE_RESERVED_PREFIXES` / `MARKETPLACE_SYSTEM_PUBLISHERS` | `olo,ololabs,toolgate,examples` / `examples` | Reserved id prefixes, and the slugs that may still sign their own |
| `MARKETPLACE_S3_ENDPOINT`, `_REGION`, `_BUCKET`, `_ACCESS_KEY`, `_SECRET_KEY` | none | Object storage the uploads are read from |
| `MARKETPLACE_HELPER_FILE_ROOT` | unset | Enables `file:` references under this folder; tests and the seed check only |
| `TOOLGATE_CLI` | `/opt/olo/bin/toolgate` | The CLI |

## Tests

`ci/run.sh` runs them with `TOOLGATE_DIR`, `TOOLGATE_CLI` and a freshly built seed:

```bash
marketplace/ci/run.sh
```

To run only the helper's tests: `cd helper && TOOLGATE_DIR=… MARKETPLACE_DIR=… TOOLGATE_CLI=… ./gradlew check`.
