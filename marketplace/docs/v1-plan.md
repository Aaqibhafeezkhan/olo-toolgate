# ToolGate Marketplace v1: simple plan

Status: plan, not implemented. This replaces the phased design in [long-term-plan.md](long-term-plan.md) for the **first version**. long-term-plan.md stays as the later direction.

## 1. Scope of v1

In:
- Free tools only, listed on a Drupal website.
- Users register, log in and upload a `.tgpkg`.
- A **helper container** reads the package's listing JSON, then validates and signs it from a **queue**.
- The signed package goes back to the owner, who clicks **Publish**.
- Once it's published, anyone can see it in the Drupal listing and download it, and any ToolGate admin can pull it from a public URL.
- The base URL is `https://olo-labs.duckdns.org` for now. It's one setting, so moving to the production domain later changes nothing else.

Out of v1: paid tools, moderators, ratings and reviews, verified publishers, sandbox execution, advisories, automatic updates in ToolGate, and the separate Java Marketplace API.

## 2. Components

```text
                    https://olo-labs.duckdns.org   (reverse proxy + Let's Encrypt)
                                   |
        +--------------------------+---------------------------+
        |                                                      |
+-------v-----------------------+        internal network      |
| Drupal (website + data)       |<---------------------------+ |
|  users, login                 |  1. POST /inspect (sync)   | |
|  Tool / Tool version entities |  2. GET  /jobs/next        | |
|  private files: uploads       |  3. POST /jobs/{id}/result | |
|  public files: signed pkgs    |                            | |
|  job queue (DB table)         |       +--------------------+-+
|  public JSON catalog API      |       | Helper container         |
+-------------------------------+       |  toolgate CLI (validate)  |
        |                               |  listing extraction       |
   MariaDB/PostgreSQL                   |  signing key (Ed25519)    |
                                        |  no public port           |
                                        +---------------------------+
```

- **Drupal** owns all data in v1: accounts, tools, versions, files, the job queue and the public pages and JSON.
- **Helper container** has no database and no public port. It only reads packages, validates them and signs them. The signing key lives only here, mounted as a secret, never in Drupal.
- **Reverse proxy** (Caddy or Traefik) gives HTTPS on the DuckDNS name with Let's Encrypt, and routes only to Drupal.
- Everything runs from one `docker compose` file: `drupal`, `db`, `helper`, `proxy`.

## 3. The flow

### Step 1. Register and log in

Drupal core accounts with email verification and a CAPTCHA on the sign-up form. Any verified user can upload.

### Step 2. Upload, and the listing is read straight away

The user opens **My tools → Upload package** and picks a `.tgpkg` file built with `toolgate pack`.

1. Drupal stores the file in **private** storage, so it can't be downloaded yet.
2. Drupal calls the helper synchronously: `POST /inspect` with the file. The helper returns the listing JSON:
   - from `marketplace/listing.json` when the package has one;
   - otherwise built from `toolgate-package.json`: id, name, version, publisher, description, and each tool's name, title and description, plus the settings the tool needs (globals, secrets, destinations). The Java SDK doesn't write `listing.json` yet, so this fallback is what v1 relies on.
3. Drupal fills the form from that JSON (name, summary, category, tags, license, tools). The user checks it and saves. The version is now **QUEUED**.

If `/inspect` fails (not a zip, no manifest), the upload is rejected on the spot with the reason.

### Step 3. Queue

Drupal adds a job row: `{jobId, toolId, version, fileUri, createdAt, status: QUEUED}`.

**"Take the latest request":** for one tool version, only the newest upload is processed. If the user uploads again before the job runs, the older job is marked **SUPERSEDED** and skipped. Different tools are processed oldest first, so no one waits forever.

### Step 4. Validate and sign (helper container)

The helper polls `GET /api/helper/jobs/next` every few seconds with a shared token. Drupal hands out one job with a lease of five minutes; if the helper crashes, the job goes back to the queue when the lease runs out.

The helper downloads the file over the internal network and checks it:

| Check | How |
|---|---|
| Package is well formed | `toolgate validate` (the same archive, checksum, manifest and descriptor checks Control runs on upload) |
| Size | Max 50 MB (configurable) |
| Owner | The id prefix belongs to this user. The first upload of a prefix claims it; others can't use it. |
| Version | That id and version isn't already published |
| Runtime | Java server tool, the only kind ToolGate runs today |
| License | An SPDX license in the listing, or the user picked one in step 2 |

When everything passes, it signs a small **release record**:

```json
{
  "packageId": "acme.notify",
  "version": "0.1.0",
  "packageDigest": "sha256:…",
  "descriptorSetDigest": "sha256:…",
  "publisher": "acme",
  "signedAt": "2026-10-12T17:30:00Z",
  "keyId": "olo-marketplace-2026-1",
  "signature": "base64 Ed25519 over the canonical JSON above"
}
```

The package file itself is **not changed**. Its digest covers every file and excludes signatures by design, so the release record is a detached `.release.json` next to it.

The helper posts the result to `POST /api/helper/jobs/{id}/result`: either `SIGNED` with the release record, or `FAILED` with a readable reason.

### Step 5. Signed, ready to publish (owner only)

The version page shows **Signed, ready to publish** with the digest and the release record. The owner can download the signed package to check it. Only the owner can see it at this point.

### Step 6. Publish

The owner clicks **Publish**.
- Drupal copies the package to public storage at `/files/packages/<id>/<version>/<id>-<version>.tgpkg`, along with `<id>-<version>.release.json`.
- The version becomes **PUBLISHED**. It's immutable, so a fix is always a new version.
- The tool shows in the public listing with its tools, required settings, license, version history and a download button.
- The owner can **Unpublish** later. The version is then hidden from the listing and the catalog, but kept, and the same id and version can't be reused.

### Step 7. Public listing and download

Anyone, without logging in, can:
- browse and search tools by name, category and tag;
- open a tool and see its versions, tools, the settings it needs, and its digest;
- download the `.tgpkg` and its `.release.json`.

### Step 8. ToolGate admin pulls it

Drupal publishes a small read-only JSON API under the base URL:

| URL | Returns |
|---|---|
| `GET /api/toolgate/v1/catalog` | All published tools: id, name, summary, latest version |
| `GET /api/toolgate/v1/tools/{id}` | One tool with every published version |
| `GET /api/toolgate/v1/tools/{id}/{version}` | Listing JSON, digest, download URL, release URL |
| `GET /api/toolgate/v1/tools/{id}/{version}/package` | The `.tgpkg` |
| `GET /api/toolgate/v1/tools/{id}/{version}/release` | The `.release.json` |
| `GET /.well-known/toolgate-marketplace-keys.json` | Public signing key(s) by `keyId` |

There are two ways in, and both end in the upload and deploy flow ToolGate already has:
- **Works on day one:** the admin downloads both files from the website and uploads the `.tgpkg` on the console's Server packages page.
- **Small v1 addition in ToolGate:** Control gets a `marketplace` setting with the base URL (`https://olo-labs.duckdns.org`) and the pinned public key. The console gets **Import from Marketplace**, where the admin pastes a tool URL or picks from the catalog. Control then:
  1. downloads the package and its release record;
  2. checks the package digest against the release record;
  3. verifies the signature with the pinned key;
  4. runs the existing upload.

After that the steps are unchanged: deploy with values and secrets, enable after review, grant to an Agent Group, and the agent calls the tool through `/mcp`. Production never calls the Marketplace at runtime, because Control keeps its own copy of the bytes.

When the domain changes, only two things change: the Drupal base URL setting, and the `marketplace.baseUrl` value in each ToolGate. The key and the URL paths stay the same.

## 4. Version states

```text
UPLOADED ─► QUEUED ─► VALIDATING ─► SIGNED ─► PUBLISHED ─► UNPUBLISHED
                 │          │
                 ▼          ▼
            SUPERSEDED    FAILED
```

## 5. What gets built

| Part | Work |
|---|---|
| Drupal (`apps/marketplace-drupal`) | Composer project; custom module `toolgate_marketplace` with Tool and Tool version entities, the upload form, the job table and the helper endpoints (token-protected and reachable only on the internal network), the publish and unpublish actions, the listing and detail pages, the public JSON API and the keys file; Search API with the database backend |
| Helper container (`apps/marketplace-worker`, reusing the scaffold) | A small Java service: `POST /inspect`, a poll loop for jobs, validation via the `toolgate` CLI, listing extraction, Ed25519 signing, and a result callback. Image includes the `toolgate` binary and a JRE. |
| Control and console | The `marketplace` setting (base URL and pinned keys), an import endpoint that fetches, verifies and calls the existing upload, and an **Import from Marketplace** dialog on the Server packages page |
| Deploy | `deploy/compose/Marketplace/compose.yaml` with Drupal, the database, the helper and Caddy on `olo-labs.duckdns.org`; a DuckDNS updater if the IP is dynamic; volumes for files and DB; key generation script |
| Seed | Publish the 17 working PR #89 example packages under the `examples` prefix as the first listings |

**Done when:** a new user registers, uploads `webhook-notify-0.1.0.tgpkg`, sees the form filled from the listing, gets **Signed**, publishes it, and sees it in the public listing. A ToolGate Quickstart admin then imports it from `https://olo-labs.duckdns.org`, deploys it, enables and grants it, and calls `notify.send` through `/mcp`.

## 6. Safety kept even in v1

- Nothing executes uploaded code: the helper only reads and checks files.
- Unsigned or failed packages are never public; only signed and published files sit in public storage.
- The signing key stays in the helper container. Drupal and the browser never see it.
- ToolGate verifies the signature and digest itself and still requires an admin to deploy, enable and grant. A listed tool gets no access in anyone's ToolGate by being listed.
- With no moderation, abuse is handled by an admin role in Drupal that can unpublish any tool and block a user.
