# ToolGate Tool Marketplace: plan

Status: plan, revision 1, not implemented. Checked against `olo-labs/olo-toolgate` main after PR #89 (2026-10-12).
Step-by-step flow for the PR #89 tools: [end-to-end-flow.md](end-to-end-flow.md).
Companions: [tool-sdk plan](https://github.com/olo-labs/olo-toolgate/blob/main/docs/sdk-plan/plan.md) (package format §7, upload and lifecycle §8, kill switch §15), [publishing plan](https://github.com/olo-labs/olo-toolgate/blob/main/docs/sdk-plan/publishing-plan.md) (P-5 Marketplace), and the repo's module specs `docs/codex/modules/12-marketplace-api.md` to `15-marketplace-control-integration.md`.

## 1. Goal

A public website where a community developer can:
1. register and log in;
2. claim a publisher namespace;
3. publish a ToolGate tool package (`.tgpkg`) and new versions of it;

and where a ToolGate administrator can:
4. find a tool, see exactly what it needs and what it is allowed to do before installing;
5. pull a signed version into their own ToolGate, configure it and run it in production under the normal review, enable, grant and audit flow;
6. hear about updates, advisories and revocations, and act on them.

The rule from `docs/marketplace/README.md` stays the spine of the design: **the community publishes capability, organization administrators grant authority, and the Gateway authorizes every call.** Nothing installed from the Marketplace runs with more than an admin approved, and production never depends on the Marketplace being up.

## 2. What exists today

| Piece | State on main | What the Marketplace reuses |
|---|---|---|
| Package format `.tgpkg` | Done. Deterministic zip, `CHECKSUMS.sha256`, package digest = SHA-256 of the checksums file, optional `marketplace/listing.json`, `README.md`, `CHANGELOG.md`, `icon.png`, `tests/self-tests.json`, `sbom/cyclonedx.json` (`tool-sdk/cli/src/package.rs`) | The unit of publishing. Same bytes from developer to production. |
| `toolgate` CLI | `init`, `dev`, `check`, `pack`, `validate`, `verify`, `digest`. No `upload` or `publish` yet | `validate` runs in the Marketplace worker; `publish` is added later |
| Java SDK + 18 example packages (PR #89, merged) | Fat-jar `.tgpkg` per example, `@Example` self-tests, `run-java-examples.sh` runs each jar with `java -jar` in an empty environment and lists its tools | Seed catalog (an official `olo-labs` publisher) and the template for the sandbox self-test step |
| Control package upload and deploy | `POST /api/control/v1/packages/uploads` (Super Admin, verifies archive, digests, stores sealed bytes, state `VERIFIED`), `GET /packages`, `POST /packages/deployments` (globals, destination hosts, Tool Host placement; tools land disabled), console page `ServerPackages.tsx` | Import ends in exactly this path. No new deploy flow. |
| `MarketplaceRelease` | v1 schema only (`marketplaceKeyId`, `marketplaceSignature`, "Marketplace trust only; never organization or runtime authorization") | Becomes the detached release signature, moved to v2 and bound to `packageDigest` |
| Marketplace API, Worker, Drupal | Scaffolds only: `apps/marketplace-api` and `apps/marketplace-worker` have an empty module class, `apps/marketplace-drupal` has a README. Module specs 12 to 15 list deliverables. | This plan fills them |
| PHP contract bindings | `packages/contracts/php` generated with the other languages | The Drupal client module uses them, so Drupal and the API can't drift |

Gaps found while checking (each becomes a work item below):
- The Java SDK does not generate `marketplace/listing.json` yet; `pack` only copies it if present. plan.md §7.5 says it is generated from `@ToolPackage` plus build metadata.
- Control's upload accepts and ignores `signatures/`; it never checks a Marketplace signature and records no source.
- Control runs Java server packages only today (M2). Python, TypeScript and .NET governed runtimes come in M5, so the Marketplace accepts **Java server-tool packages** first and opens other runtimes as Control supports them.

## 3. Architecture

```text
 Community developer                         ToolGate admin (an organization)
   |  browser / toolgate publish               |  ToolGate console
   v                                           v
+-------------------------+            +---------------------------+
| Drupal website          |            | Control (existing)        |
|  accounts, login, 2FA   |            |  Marketplace source       |
|  publisher pages        |            |  (URL + pinned keys)      |
|  browse, search, detail |            |  import -> upload path    |
|  upload UI, job status  |            |  -> review -> deploy ->   |
|  moderation UI, reviews |            |  enable -> grant          |
+-----------+-------------+            +------------+--------------+
            | OAuth (user token)                    | HTTPS, public catalog + download by digest
            v                                       v
+------------------------------------------------------------------+
| Marketplace API (Java, stateless, authoritative for packages)     |
|  publishers, namespaces, packages, versions, submissions,         |
|  upload sessions, jobs, moderation, releases, advisories, catalog |
+------+--------------------+--------------------+------------------+
       | PostgreSQL         | Object storage     | Job queue (DB-leased)
       |                    | quarantine/ + releases/
       v                    v
+------------------------------------------------------------------+
| Marketplace Worker (Java)                                         |
|  validate -> scans -> sandbox self-test -> diffs -> queue review  |
|  -> sign via KMS -> promote to immutable storage                  |
+------------------------------------------------------------------+
```

**Why Drupal is not the package store.** Drupal gives us accounts, login, profiles, content, search pages, community reviews and moderation screens quickly. It does not hold package records, artifacts or the signing key, for three reasons:
- the signing key and the decision "this exact digest is a release" must sit behind a small, audited service, not a CMS with a large plugin surface;
- Control talks to a stable, versioned registry API (the same protocol later serves private and air-gapped registries, `docs/marketplace/private-registries.md`), not to Drupal internals;
- hostile uploads are processed by an isolated worker, never by the web tier.

This is the split the repo already chose (module 14: "consuming Marketplace API without owning package business data"; "no direct Marketplace DB mutation; no package execution"). Decision 1 in §12 lets Rahul trade it for a Drupal-only MVP.

### 3.1 Where each kind of data lives

| Data | Owner |
|---|---|
| User accounts, passwords, 2FA, profiles, avatars | Drupal |
| Community reviews, ratings, comments, docs pages, abuse reports | Drupal |
| Publishers, namespaces, members and roles | Marketplace API (Drupal shows them) |
| Packages, versions, states, artifacts, scan reports, moderation decisions, release signatures, advisories, download counts | Marketplace API |
| Search index of the public catalog | Drupal (Search API), rebuilt from Marketplace API webhooks, with a periodic full resync |

## 4. Identity and accounts

- **Registration:** Drupal core accounts with email verification, CAPTCHA/honeypot on the form, and optional "Sign in with GitHub" (OpenID Connect contrib module). Rate limits on signup and login.
- **2FA:** optional for readers, **required** before a user can own a namespace or publish (TFA module, TOTP or WebAuthn).
- **Tokens to the Marketplace API:** Drupal is the OAuth 2 authorization server for community users (`simple_oauth`). The website calls the API with the user's short-lived access token (JWT, audience `marketplace-api`); the API verifies it against Drupal's JWKS and applies its own ownership checks, so a compromised Drupal session can only do what that user could.
- **Scopes:** `catalog:read`, `publisher:manage`, `package:publish`, `package:yank`, `moderation:review`, `moderation:admin`.
- **CLI and CI:** personal publish tokens created on the website, scoped to one namespace and `package:publish`, with expiry, shown once and stored hashed. Later (MK3) GitHub Actions trusted publishing via OIDC, so CI needs no stored token.
- **Roles:** reader, publisher member (`owner`, `maintainer`), moderator, Marketplace admin. Moderators and admins need 2FA and are not self-service.

## 5. Publishers and namespaces

- A user creates a **publisher** (display name, slug, website, contact, security contact). The slug is the package id prefix: publisher `acme` can only publish ids `acme.*` (plan.md §19: "package id prefixes are bound to verified Marketplace publishers").
- **Reserved:** `olo`, `ololabs`, `toolgate`, `io.ololabs` and close variants; the official examples publish as `olo-labs`.
- **Typosquatting guard:** a new slug or package id within a small edit distance of a verified publisher's is held for moderator approval.
- **Verified publisher** badge after proving a domain (DNS TXT) or a GitHub organization. Verified status matters for the auto-publish rule in §6.4 and is shown on every listing.
- Members: owners add or remove maintainers; the last owner can't leave; ownership transfer needs both sides and a moderator.
- Publisher keys (optional): a publisher can register Sigstore identities (for example its GitHub repo workflow) so its own publisher signature in `signatures/` is checked (plan.md §7.3, §7.6).

## 6. Publishing a tool

### 6.1 Developer flow

```text
toolgate pack            ->  acme.orders-1.4.0.tgpkg   (exists today)
website: "New version" (drag the file)   or   toolgate publish (MK2)
  -> upload session: API returns a presigned URL into quarantine storage
  -> browser/CLI uploads the bytes directly; API records size and SHA-256
  -> submission created, job queued, status page shows each step live
```

The website never stores the file itself and never serves anything from quarantine.

### 6.2 Version states

```text
DRAFT -> SUBMITTED -> PROCESSING -> IN_REVIEW -> PUBLISHED -> DEPRECATED
                          |              |            |
                          v              v            +-> YANKED   (hidden from new installs, still downloadable by digest)
                       FAILED         REJECTED        +-> REVOKED  (security: advisory raised, download refused)
```

A published version is **immutable**: the same `id@version` can never point at other bytes. A fix is always a new version. `docs/marketplace/package-lifecycle.md` already states this.

### 6.3 Worker pipeline (every submission)

| # | Step | Uses | Fails the submission when |
|---|---|---|---|
| 1 | Archive and digest checks | `toolgate validate` (same Rust code as the CLI) and the Java `PackageArchive` rules Control applies, so the Marketplace never accepts what Control would reject | Any §7.6 zip rule, checksum, descriptor or manifest check |
| 2 | Ownership and identity | Manifest `id` prefix = publisher slug; `publisher` field matches; version is new and SemVer | Mismatch or reused version |
| 3 | Supported kind | Java server tools first (§2) | Kind or runtime Control can't run yet |
| 4 | Listing completeness | `marketplace/listing.json` (§7.5): summary, categories, SPDX license, links, icon, `setup` | Missing required listing fields |
| 5 | Secret scan | gitleaks over every file and inside jars | A live-looking credential |
| 6 | Malware scan | ClamAV over the archive and nested jars | Any detection |
| 7 | SBOM and vulnerabilities | Use `sbom/cyclonedx.json` if present, else generate (Syft or Trivy); scan (Trivy/Grype) | Critical CVE with a fix available (configurable) |
| 8 | Sandbox self-test | Run the fat jar the way `run-java-examples.sh` does: `java -jar` in an empty environment, list tools, then run `tests/self-tests.json` (`@Example` cases), inside gVisor or Firecracker with **no network**, CPU, memory and time caps | Tools listed differ from the descriptor, or a self-test fails |
| 9 | API-compatibility diff | Previous published version vs this one (plan.md §8.3): removed tool or changed required input needs a major bump | Bump too small |
| 10 | Security delta | Permissions, egress hosts, secrets, connections, claims, effect class, runtime tier, publisher key, service-profile widening (plan.md §8.3) | Never fails; it decides whether review is needed |
| 11 | Moderation queue or auto-publish | §6.4 | Rejected by a moderator |
| 12 | Sign | KMS signs a `MarketplaceRelease` v2 over `{packageId, version, packageDigest, descriptorSetDigest, publisher, publishedAt}`; key id published at a well-known URL | KMS error (retried; never publish unsigned) |
| 13 | Promote | Copy from `quarantine/` to `releases/<digest>.tgpkg`, write-once, then mark `PUBLISHED` and fire webhooks | Storage error (retried) |

Steps are idempotent with a durable job lease, so a crash or duplicate delivery never publishes twice (module 13's mandatory tests).

### 6.4 Moderation rule (default, see decision 3)

- **Human review:** the first version of every package, every version with a non-empty security delta, every binary-only package without a source link, and anything from an unverified publisher that asks for egress or secrets.
- **Auto-publish:** a version from a verified publisher whose security delta is empty and whose scans and self-tests passed.
- The moderator screen shows the listing, tools and schemas, the security delta in plain words ("adds egress to api.example.com:443", "adds secret ApiKey"), scan and self-test output, and the SBOM summary. Decisions are recorded with a reason and shown to the publisher.

## 7. The website (Drupal)

### 7.1 Pages

| Page | Who | Content |
|---|---|---|
| Home and browse | Everyone | Featured, newest, most installed, categories, search with filters (category, publisher, verified, license, effect class, needs egress, needs secrets) |
| Package detail | Everyone | Sanitized README, tools table (name, description, effect safety, input schema rendered as a form preview), **"What this tool needs"** from `setup` (globals, secrets, connections, egress hosts, claims), runtime, versions and changelog, scan status, SBOM, security delta of the latest version, install counts, community reviews, a "Use in ToolGate" panel with the package id, version and digest and a copy button for the import, and a download link |
| Publisher profile | Everyone | Verified badge, members, packages, security contact |
| My publishers / packages | Publisher members | Create publisher, members, tokens, new version upload, submission status, deprecate, yank |
| Submission status | Publisher members | Live pipeline steps with logs a publisher may see (never scanner internals that help evade) |
| Moderation queue | Moderators | §6.4 review screen, advisories, abuse reports, publisher verification |
| Advisories | Everyone | Published security advisories with affected versions |
| Docs | Everyone | How to build a tool (links into the SDK docs), how to publish, how to import into ToolGate, publisher policy, takedown and security reporting |

### 7.2 Build

- Drupal current major (11.x at the time of writing), PHP 8.3+, Composer-managed, config in code (`config/sync`), Drush deploys.
- Contrib: `simple_oauth`, `openid_connect` (GitHub), `tfa`, `search_api` (database backend first, Solr or OpenSearch later), `honeypot`/`captcha`, `symfony_mailer`, `redis`.
- Custom modules in `apps/marketplace-drupal/web/modules/custom/`:
  - `toolgate_marketplace_client`: typed client over the generated PHP contract bindings; error mapping, retries, caching of public catalog reads;
  - `toolgate_marketplace_catalog`: browse, search index sync from webhooks, detail pages;
  - `toolgate_marketplace_publisher`: publisher, token and upload screens (presigned upload from the browser);
  - `toolgate_marketplace_moderation`: queue and decision screens.
- Theme: a sub-theme of Olivero with the ToolGate look, accessible (WCAG 2.2 AA).
- **Markdown safety:** README and changelog rendered with CommonMark in safe mode (no raw HTML, no `javascript:` links), images only from the release's own `icon.png` or proxied, plus Drupal's XSS filter; JSON schemas rendered as text, never HTML.

## 8. Pulling a tool into ToolGate and running it in production

### 8.1 Admin flow

```text
ToolGate console -> Packages -> Marketplace
  search (Control queries the Marketplace catalog API)
  -> package detail: same "what this needs" and security summary as the website
  -> Import version X
Control:
  1. Download releases/<digest>.tgpkg and its MarketplaceRelease.
  2. Check SHA-256 of the CHECKSUMS file = packageDigest in the release.
  3. Verify the release signature against the Marketplace keys pinned in Control settings.
  4. Refuse if the version is YANKED or REVOKED, or an advisory blocks it.
  5. Run the existing upload path (PackageArchive.validate, sealed store, state VERIFIED),
     recording source = marketplace, release, publisher and verified status.
  6. Optional organization re-scan (tenant policy) before it can be deployed.
Then the existing steps, unchanged:
  deploy (globals, destination hosts, Tool Host placement) -> tools land disabled
  -> reviewed enable (security delta shown) -> grant to Agent Groups -> catalog revision
```

- **Production never calls the Marketplace at runtime.** Control already stores the exact bytes, so the import is the mirror. Tool Hosts fetch from Control by digest, as today.
- **Air-gapped:** the website's download gives the `.tgpkg` plus a `.release.json`; the console's existing upload accepts both and runs steps 2 to 6.
- **Permissions:** import is Super Admin only, like upload today. A separate "Marketplace import" permission can come later.
- **Org trust policy** (Control settings): allowed Marketplace URL(s) and pinned keys; "verified publishers only"; allow-list or block-list of publishers; require org re-scan; block packages with critical CVEs.

### 8.2 Updates, advisories and revocation

- Control polls the Marketplace (default every 15 minutes) for new versions of installed package ids and for advisories; webhooks to Control are optional and polling stays as the fallback (module 15).
- Console shows "Update available" with the API diff and security delta; importing the update follows §8.1 and the lifecycle in plan.md §8.4 (rollout, rollback).
- Auto-update channels `MANUAL` (default), `PATCH`, `MINOR` arrive with plan.md §8.4 P1; they always stop at review when the security delta is non-empty.
- **Revocation:** a revoked version raises a console alert and an audit event, and offers one-click suspend through the kill switch (plan.md §15). Whether it suspends automatically is decision 4.
- Advisories are signed by the same Marketplace key and verified before Control acts on them; a forged advisory is ignored and logged.

## 9. Marketplace API surface (first cut)

Public, no auth:
- `GET /v1/catalog/packages?q=&category=&publisher=&verified=&page=`
- `GET /v1/catalog/packages/{id}` and `/{id}/versions`, `/{id}/versions/{version}`
- `GET /v1/releases/{digest}` (the `MarketplaceRelease`) and `/v1/releases/{digest}/package` (302 to immutable storage)
- `GET /v1/advisories?since=`
- `GET /.well-known/toolgate-marketplace-keys.json`

Authenticated (user token or publish token):
- publishers: create, read, update, members, tokens, verification
- packages: create; versions: create upload session, complete upload, submit, read status, deprecate, yank
- moderation: queue, decide, raise advisory, revoke

Rules: idempotency keys on every write, ETag/`If-Match` on updates, per-user and per-token rate limits, OpenAPI in `packages/contracts/openapi/marketplace-v1.yaml`, and webhooks (`version.published`, `version.yanked`, `version.revoked`, `advisory.published`) signed with HMAC and replay-protected.

## 10. Security summary

- Uploads go to quarantine storage; nothing there is served. Only signed releases are promoted to write-once storage.
- Packages execute **only** inside the worker's sandbox (no network, caps, ephemeral), never in Drupal or the API.
- Signing key lives in KMS/HSM; the worker sends a digest and gets a signature back (`docs/marketplace/signing.md`).
- Marketplace signature means "the Marketplace reviewed and published these bytes", never "allowed to run". Authority comes only from the org admin's enable and grant, and every call still goes through the Gateway, policy, permits and audit.
- 2FA for publishers and moderators, scoped and expiring tokens, rate limits, typosquatting guard, reserved names.
- Abuse and takedown: report button on every listing, moderator SLA, publisher suspension, revocation with advisory.
- Threat model added at `docs/security/threat-models/marketplace.md` before code (supply chain, account takeover, malicious package, Drupal compromise, key compromise and rotation).

## 11. Delivery phases

| Phase | Ships | Exit |
|---|---|---|
| **MK0: design** | ADR for the Marketplace (this split, trust model, MVP scope); `marketplace-v1.yaml` OpenAPI; `MarketplaceRelease` v2, `Advisory` and `listing.json` schemas with fixtures; threat model; Java SDK generates `marketplace/listing.json` from `@ToolPackage` | Reviews signed off; contract checks in `preflight.yml` green |
| **MK1: community catalog (MVP)** | Marketplace API and Worker (steps 1 to 13, manual moderation for everything); Drupal site with accounts, 2FA, publishers, browser upload, status, browse, detail, download, moderation queue; the 18 PR #89 examples published as `olo-labs`; Control verifies a `.release.json` on console upload and records the source | A user registers, publishes `acme.system-health`, a moderator approves it, an admin downloads it, uploads it to Quickstart, deploys, enables, grants and calls it through `/mcp` |
| **MK2: one-click import** | Console "Marketplace" page backed by Control (search, detail, import by digest), update notifications, advisory and revocation sync, `toolgate publish` and `toolgate upload` CLI, auto-publish rule for verified publishers | Import-then-deploy E2E and publish-then-second-org-import E2E (module 15 tests) pass, plus forged signature, hash mismatch, revoked package and Marketplace-outage cases |
| **MK3: community and scale** | Verified publisher flow, ratings and reviews, GitHub trusted publishing, auto-update channels, private and partner registries over the same API, org account linking and publishing from ToolGate, other runtimes as M5 lands | Per item |

Deployment: Docker images for the website, API and worker; Helm charts next to `deploy/helm/olo-toolgate` (`marketplace-api`, `marketplace-worker`, `marketplace-drupal`, with external PostgreSQL/MariaDB and S3-compatible storage); a `deploy/compose/Marketplace` stack with MinIO and a local KMS stand-in so a developer can run the whole loop next to QuickStart.

## 12. Decisions only Rahul can make

| # | Decision | Default I picked |
|---|---|---|
| 1 | **Architecture.** Drupal for the website plus a small Java Marketplace API and Worker that own packages and signing (the repo's existing design), or Drupal does everything for a faster MVP | Drupal + Java API/Worker. Drupal-only puts the signing key and hostile uploads inside the CMS and would be thrown away for private registries. |
| 2 | **Who can publish at launch.** Open signup with moderation, or invite-only beta | Open signup for reading and accounts; publishing invite-only for MK1, open from MK2 |
| 3 | **Review policy.** Human review of every version, or auto-publish delta-free versions from verified publishers | Human review for everything in MK1; the auto-publish rule in §6.4 from MK2 |
| 4 | **Revoked tool already in production.** Alert plus one-click suspend, or automatic suspend through the kill switch | Alert plus one-click suspend; automatic suspend as an org opt-in for critical advisories |
| 5 | **Hosting and domain.** Who runs it (our Kubernetes with these Helm charts, or a managed Drupal host for the website) and the domain (for example `marketplace.ololabs.io`) | Our Kubernetes for all three; domain open |
| 6 | **Commercial scope.** Free tools only, or paid listings later | Free only, any SPDX license; binary-only packages get stricter review. Paid listings are out of scope. |
