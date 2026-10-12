# Review: ToolGate Marketplace v1 design (design.md 1.0)

Checked against `olo-labs/olo-toolgate` main (after PR #89) and [v1-plan.md](v1-plan.md).

**Verdict: sound and buildable; approve after the fixes below.** The boundaries are right: Drupal owns listings, ToolGate only consumes them, nothing executes during validation, and import ends in Control's existing upload with no automatic deploy, enable or grant. Its own "assumptions to verify" in §3 hold:
- `toolgate pack` and `toolgate validate` exist;
- `POST /api/control/v1/packages/uploads` exists;
- the console has a Server packages page.

The fixes are mostly about matching the real package format and two places where ToolGate's own code would block the design.

## Must fix

| # | Where | Problem | Fix |
|---|---|---|---|
| 1 | §6, §7, §8.3 | **Two different digests.** The design uses "SHA-256 of the exact archive bytes". ToolGate identifies a package by `packageDigest` = SHA-256 of `CHECKSUMS.sha256`, not of the zip (tool-sdk plan §7.2). Control's upload returns, pins and deploys by `packageDigest`. A single `sha256` field will be compared against the wrong value. | Expose both: `archiveSha256` (download integrity) and `packageDigest` (identity). Control checks `archiveSha256` after download, revalidates, then requires the `packageDigest` it computes to equal the catalog's before upload. |
| 2 | §6 helper | **`toolgate validate` on a directory compiles and runs tests.** Its help text says "a project directory runs check" (`tool-sdk/cli/src/main.rs:24`). That breaks the no-execution rule if the helper ever passes a directory, for example an unpacked upload. | The helper only ever passes a single regular file it wrote itself, and refuses anything else. Better still, also run Control's Java check (fix 3). |
| 3 | §6 helper | **Two validators can disagree.** The Rust CLI and Control's Java `PackageArchive.validate` are separate code. Control adds rules of its own, for example that each Java tool has exactly one jar artifact (`PackageArchive.java:81`). A package the Marketplace lists could then fail on import. | Make the helper a small Java service in the existing `apps/marketplace-worker` scaffold that calls the same `PackageArchive` code Control uses (extract it to a shared library), with the CLI as a second check. "Listed" then means "Control will accept it". |
| 4 | §8.1 | **The console can't show Marketplace images.** The console's Content-Security-Policy is `img-src 'self'` (`ConsoleAssets.java:27`), so avatars, icons and screenshots from `olo-labs.duckdns.org` won't load. Loosening the CSP to fix this would be a step backwards. | Control proxies the images through a bounded, size-limited `GET` on its own origin (same SSRF rules as package fetch), or v1 shows initials instead of avatars and no screenshots in the console. |
| 5 | §4.2, §5.2 | **Ownership per package id lets people squat names.** "The first publisher to reserve an id owns it" lets anyone take `acme.notify` before Acme arrives, and nothing stops a near-copy of an id. | Bind the id **prefix** to the publisher slug: publisher `acme` owns `acme.*` (tool-sdk plan §19). Reserve `olo`, `ololabs`, `toolgate` and `examples`; the PR #89 packages all use `examples.*` and should be published by an OLO Labs account. |
| 6 | §6, §7 | **`requiredSettings` as a flat list loses what the admin needs.** The real descriptor has typed `variables.globals`, `variables.secrets`, claims and `destinations` with `hostSetting` (`packages/contracts/schemas/v2/descriptor.schema.json`). For `examples.notify` the needs are destination `WebhookHost`, secret `WebhookApiKey`, and globals `AllowedChannels` and `Source` with defaults, not `webhookUrl`/`webhookSecret`. | Return `requiredSettings: [{kind: global\|secret\|destination\|claim, name, type, default, description}]` built from the descriptors. Also return each tool's effect safety, so the admin sees "sends messages" before installing. |

## Should fix

| # | Where | Point |
|---|---|---|
| 7 | §4.3 vs §5.2 | The state diagram has no private "validated, listing not saved" state and no delisted state, though §5.2 and §11 need both. Use `UPLOADED → VALIDATING → VALIDATED (private draft) → AVAILABLE → DELISTED`, with `REJECTED` from `VALIDATING`. Expire `VALIDATED` drafts after a day. |
| 8 | §7 | There's no endpoint listing a tool's versions, so the admin can only ever see what the catalog calls latest. Add `GET /api/toolgate/v1/tools/{id}`, and say whether a catalog item is per package (latest version) or per version. |
| 9 | §8.3 | The digest check against the same server only catches a broken download, not a tampered one, because the same origin serves both values. The design says so in §9; the import dialog and docs should say the same, and Control's local revalidation is the real check. |
| 10 | §10 | Control environment variables follow `TOOLGATE_CONTROL_*` (for example `TOOLGATE_CONTROL_CLIENT_CHECKIN_MS`). Use `TOOLGATE_CONTROL_MARKETPLACE_ENABLED` and `TOOLGATE_CONTROL_MARKETPLACE_BASE_URL`. Treat them as environment-owned settings, so a configuration import can't redirect the fetch origin. |
| 11 | §5.2 | Package size: Control's upload accepts at most 64 MiB (`BodyLimit.java`). The 50 MB default fits; state that the Marketplace maximum must stay below Control's. |
| 12 | §8.3 | Import must reuse the upload's Super Admin check (`ServerPackages.upload`), and must record the source (Marketplace origin, package id, version, publisher slug) in the audit event so an imported package can be traced to its listing. |
| 13 | §5.2 | The Java SDK doesn't generate `marketplace/listing.json` yet; `pack` only copies one if present. The fallback to `toolgate-package.json` will be the normal path in v1, so test against it first. |
| 14 | §12 | `examples/java-tools/log-insights` has no source on main (README and build files only), so it can't be one of the seed packages until that's fixed. |

## Different from what you asked earlier

These are fine if intended, but they reverse your message from 17:24:

| Earlier ask | This design |
|---|---|
| Validate **and sign** the package | No signing (§2, §14) |
| Push to a **queue**, take the latest request | Synchronous validation, no queue |
| Signed binary goes back to the user, **who then publishes** | Becomes public as soon as the listing is saved |

My view: dropping the queue is fine at this size. Signing is the one I'd keep, because it's cheap (an Ed25519 key in the helper and a detached `.release.json`). It's also the only thing that stops a compromised Drupal server from swapping packages that ToolGate admins will import. If you drop it for v1, keep the `release` field reserved in the API so adding it later doesn't break Control.

## Small notes

- §7 "Use appropriate cache headers": the download endpoint should send `Cache-Control: immutable`, because bytes never change for an id and version.
- §9 item 9: the takedown should also be visible to ToolGate. Add a `delisted` flag that the detail endpoint keeps returning, so an admin who imported the package can see it was pulled.
- §3 Mermaid diagrams render on GitHub but not in every viewer; fine for `docs/marketplace/design.md`.
