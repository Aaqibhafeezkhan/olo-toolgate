<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# OLO Orgs Marketplace API v1 (contract)

The wire contract between the helper (PR 1), the Drupal site (PR 2) and ToolGate Control (PR 3). It is derived from [design.md](design.md) 1.2. A change here needs a reason recorded in the PR that makes it. Within v1 changes are additive only (§6).

Conventions:
- JSON, UTF-8.
- Timestamps are RFC 3339 UTC.
- Digests: `packageDigest` and `descriptorSetDigest` are `sha256:<64 lowercase hex>`; `archiveSha256` is bare `<64 lowercase hex>`.
- Package ids and versions follow the v2 package schema.

## 1. Public API (Drupal, no authentication)

Base: `${MARKETPLACE_BASE_URL}/api/toolgate/v1`. Only `AVAILABLE` versions appear, except where a delisted version is noted.

| Method and path | Returns | Errors |
|---|---|---|
| `GET /catalog?page=1&limit=20&q=&category=&runtime=&tag=&publisher=` | `CatalogPage`: one item per package, latest available version | `400 INVALID_REQUEST` |
| `GET /tools/{packageId}` | `PackageVersions`: every available version, newest first, plus delisted ones with `delisted: true` | `404 NOT_FOUND` |
| `GET /tools/{packageId}/{version}` | `VersionDetail`, including for a delisted version (`delisted: true`, no `downloadUrl`) | `404 NOT_FOUND` |
| `GET /tools/{packageId}/{version}/package` | The exact `.tgpkg` bytes, `application/vnd.ololabs.toolgate.package+zip`, `Cache-Control: public, max-age=300, must-revalidate`, `ETag` = `archiveSha256` | `404 NOT_FOUND`, `410 DELISTED` |
| `GET /tools/{packageId}/{version}/release` | `ReleaseRecord` | `404 NOT_FOUND`, `410 DELISTED` |
| `GET /publishers/{slug}` | `Publisher` with its available packages | `404 NOT_FOUND` |
| `GET /media/{mediaId}` | `image/png` or `image/jpeg`, at most 1 MiB | `404 NOT_FOUND` |
| `GET ${MARKETPLACE_BASE_URL}/.well-known/toolgate-marketplace-keys.json` | `KeySet` | none |

`limit` is at most 100. Errors have the shape `{ "code": "NOT_FOUND|INVALID_REQUEST|DELISTED|UNAVAILABLE|SERVER_ERROR", "message": "…" }`.

### Objects

```jsonc
// CatalogPage
{ "page": 1, "limit": 20, "total": 18, "items": [CatalogItem] }

// CatalogItem
{ "packageId": "examples.notify", "version": "0.1.0", "title": "Webhook notify", "summary": "…",
  "category": "Operations", "tags": ["notify", "example"], "runtime": "java", "signed": true,
  "compatibility": Compatibility, "publisher": PublisherSummary, "iconMediaId": "m_…|null",
  "detailsUrl": "…/tools/examples.notify/0.1.0" }

// VersionDetail = CatalogItem plus
{ "description": "<sanitized Markdown source>", "repositoryUrl": "https://…|null", "documentationUrl": "https://…|null",
  "license": "Apache-2.0", "screenshots": ["m_…"], "tools": [Tool], "requiredSettings": [Setting],
  "packageDigest": "sha256:…", "descriptorSetDigest": "sha256:…", "archiveSha256": "…",
  "apiChange": "none|compatible|breaking", "delisted": false, "publishedAt": "…",
  "downloadUrl": "…/package", "releaseUrl": "…/release", "publisher": Publisher }

// Tool
{ "name": "notify.send", "title": "…", "description": "…", "effect": "READ_ONLY|IDEMPOTENT|NON_IDEMPOTENT" }

// Setting
{ "kind": "global|secret|destination|claim", "name": "WebhookHost", "type": "STRING|…|null", "default": "…|null", "description": "…|null" }

// Compatibility (taken from the manifest; Control decides)
{ "contractsVersion": "1.0.0", "minimumToolHostVersion": "0.10.0|null", "minimumClientVersion": "…|null",
  "protocol": { "min": 2, "max": 2 }, "descriptorGeneration": 1, "runtimes": [{ "language": "java", "version": "17" }] }

// PublisherSummary
{ "slug": "examples", "displayName": "ToolGate Examples", "organization": "OLO Labs", "avatarMediaId": "m_…|null", "profileUrl": "…/publishers/examples" }

// Publisher = PublisherSummary plus
{ "bio": "…|null", "websiteUrl": "https://…|null", "githubUrl": "…|null", "linkedinUrl": "…|null",
  "country": "…|null", "publicContactEmail": "…|null", "packages": [CatalogItem] }

// KeySet
{ "marketplace": "olo-labs-marketplace",
  "keys": [{ "keyId": "olo-marketplace-2026-1", "algorithm": "Ed25519", "publicKey": "<base64 raw 32 bytes>",
             "fingerprint": "sha256:<hex of SHA-256 over the raw public key>", "status": "current|retired" }] }
```

Publisher fields never include the login email, roles, tokens or private paths. Every value except the signed fields in `ReleaseRecord` is unsigned display data.

## 2. Release record (signed)

```jsonc
{
  "schema": "toolgate.marketplace.release/1",
  "marketplace": "olo-labs-marketplace",          // MARKETPLACE_ID, never the URL
  "packageId": "examples.notify",
  "version": "0.1.0",
  "packageDigest": "sha256:…",
  "descriptorSetDigest": "sha256:…",
  "archiveSha256": "…",
  "publisherSlug": "examples",
  "signedAt": "2026-10-12T18:45:00Z",
  "keyId": "olo-marketplace-2026-1",
  "signature": "<base64 Ed25519 signature>"
}
```

- **Signed bytes:** the RFC 8785 (JCS) canonical JSON of the object **without** the `signature` member.
- **Verification by Control:** all eight steps in design.md §8.3. Any failure rejects the import.

## 3. Helper internal API

Reachable only on the internal network. Every request carries `Authorization: Bearer ${MARKETPLACE_HELPER_TOKEN}`, and a missing or wrong token gets `401`. The only caller is Drupal.

| Method and path | Body | Returns |
|---|---|---|
| `POST /inspect` | `{ "fileRef": "s3://…", "previousVersion": { "version": "0.1.0", "fileRef": "s3://…" } or null }` | `InspectResult`. **Never contains a signature.** |
| `POST /sign` | `{ "fileRef": "s3://…", "packageDigest": "sha256:…", "authorizedPublisherSlug": "examples" }` | `ReleaseRecord` |
| `GET /keys` | none | `KeySet` plus `toolgateRevision` |
| `GET /health` | none | `{ "status": "ok" }`, with no token required |

```jsonc
// InspectResult
{ "valid": true, "errors": [{ "code": "ARCHIVE_PATH|CHECKSUM|MANIFEST|DESCRIPTOR|RUNTIME|SIZE|SEMVER|…", "message": "…" }],
  "packageId": "examples.notify", "version": "0.1.0", "runtime": "java",
  "packageDigest": "sha256:…", "descriptorSetDigest": "sha256:…", "archiveSha256": "…",
  "listing": { "title": "…", "summary": "…", "description": "…", "license": "…|null", "categories": [], "tags": [] },
  "tools": [Tool], "requiredSettings": [Setting], "compatibility": Compatibility,
  "apiChange": "none|compatible|breaking", "requiredBump": "major|minor|patch|null",
  "apiChanges": ["breaking: send input property tag is now required", "…"],   // only with previousVersion
  "warnings": ["…"] }                                                          // only when there are any
```

- `fileRef` is `s3://<MARKETPLACE_S3_BUCKET>/<key>`, read with the helper's own object-storage credentials, so any replica serves any request. Another bucket is refused. `file:<relative path>` exists only when `MARKETPLACE_HELPER_FILE_ROOT` is set (tests and the seed check).
- `previousVersion` is the previous `AVAILABLE` version of the same package: its version and its stored file. The helper diffs the two (design §6.2 B). Before 1.0 a breaking change needs a minor bump and an addition a patch (`docs/development/foundation-upgrades.md`); from 1.0, major and minor. A smaller bump gives `valid: false` with a `SEMVER` error that lists `apiChanges`.
- An invalid package is `200` with `valid: false` and its errors. `4xx` means the request itself was refused: `400 INVALID_REQUEST`, `401 UNAUTHORIZED`, `404 NOT_FOUND` for the `fileRef`, `503 UNAVAILABLE` when all validation slots are busy.
- Error codes in `errors`: `ARCHIVE`, `ARCHIVE_PATH`, `CHECKSUM`, `SIZE`, `MANIFEST`, `DESCRIPTOR`, `RUNTIME`, `SEMVER`, `TIMEOUT`.

`/sign` refuses in each of these cases:
- the token is missing or wrong: `401 UNAUTHORIZED`;
- the bytes no longer validate: `403 INVALID_PACKAGE`;
- the computed `packageDigest` differs from the one sent: `403 DIGEST_MISMATCH`;
- the manifest id prefix differs from `authorizedPublisherSlug`: `403 PREFIX_MISMATCH`;
- the prefix is reserved (`olo`, `ololabs`, `toolgate`, or `examples` for anyone but the system publisher `examples`): `403 RESERVED_PREFIX`. The lists are `MARKETPLACE_RESERVED_PREFIXES` and `MARKETPLACE_SYSTEM_PUBLISHERS`.

Drupal must have checked, from the logged-in user's session, that the user owns `authorizedPublisherSlug` (design.md §5.2).

## 4. Control settings (PR 3)

| Variable | Meaning |
|---|---|
| `TOOLGATE_CONTROL_MARKETPLACE_ENABLED` | `true` turns the Marketplace view and import on |
| `TOOLGATE_CONTROL_MARKETPLACE_ID` | Expected `marketplace` in release records |
| `TOOLGATE_CONTROL_MARKETPLACE_BASE_URL` | The only origin Control fetches from (HTTPS) |
| `TOOLGATE_CONTROL_MARKETPLACE_KEYS` | Comma-separated `keyId:base64PublicKey`; fingerprints are logged and shown |

These are environment-owned, so a configuration import can't change them.

## 5. Status and seeding

- Version states: `UPLOADED → VALIDATING → VALIDATED → AVAILABLE → DELISTED`, plus `REJECTED`.
- The seed status, exposed to admins and in the internal health endpoint, is `{ "seed": "complete|degraded", "missing": [{ "id": "…", "reason": "…" }] }`.

## 6. Versioning

- The API version is in the path (`/api/toolgate/v1`) and the release `schema` is `toolgate.marketplace.release/1`.
- Within v1, changes only add fields; clients ignore fields they don't know.
- A breaking change ships as `/api/toolgate/v2`, with v1 kept for at least two ToolGate minor releases.
- Control rejects a release `schema` it doesn't know.
