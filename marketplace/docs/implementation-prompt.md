# Implementation prompt: ToolGate Marketplace v1

Give this whole file to the implementing agent.

**Normative design:** [design.md](design.md) **version 1.2, frozen at commit `18776bbe04df26eacc2c5026e1f4defb6389da3c`** on `olo-labs/olo-toolgate` (`marketplace/docs/design.md`). Read that exact revision (`git show 18776bbe:marketplace/docs/design.md`). A later edit to design.md doesn't apply unless this line is updated to its commit. The single wire contract between the three PRs is `marketplace/docs/api.md`, written and frozen in PR 1 from that design revision.

This prompt adds four things to the design:
- where the code lives;
- how it stays separable;
- how builds resolve ToolGate;
- seeding the example packages at bring-up.

Where this prompt and the design differ, this prompt wins.

---

## Task

Build ToolGate Marketplace v1 as specified in design 1.2, in `olo-labs/olo-toolgate`, delivered as the **three PRs in §0**. The work has three parts:
1. the Marketplace itself (Drupal, helper, seed, deployment) in **one isolated folder**;
2. the small ToolGate-side changes in their normal places;
3. seeding every example package as published at container bring-up.

Before writing code, read:
- design 1.2 and `design-review.md`;
- `tool-sdk/cli/src/main.rs` and `package.rs`;
- Control's `PackageArchive.java`, `ServerPackages.java`, `PackageResource.java`, `BodyLimit.java` and `ConsoleAssets.java`;
- the v2 `package`, `descriptor` and `protocol` schemas under `packages/contracts/schemas/v2/`;
- `examples/README.md` and `examples/run-java-examples.sh`.

Confirm every interface you rely on. If something differs from the design, adapt to the code and record it in the PR body.

## 0. Delivery: exactly three pull requests

The work ships as **three PRs, in this order**. Each PR stands on its own: it builds, its tests pass, and it leaves `main` working. No fourth PR: anything found late goes into the PR it belongs to. Every PR body records what differed from the design and the test evidence.

### PR 1: Helper (validation, signing, example packages)

Branch `marketplace/pr1-helper`. Work **in this order**, one commit or more per step, each step green before the next:

1. **Regression baseline before moving any code.** Add a fixture set under `tests/fixtures/packages/`: valid packages, plus each malformed case from design §12 and Control-only rule cases such as a Java tool without exactly one jar. Add a Control test that records today's accept/reject decision and `packageDigest`/`descriptorSetDigest` for every fixture, and run it on the unchanged code.
2. **Extract** Control's `PackageArchive` (and only what it needs) into `backend-libs/package-archive` and switch Control to it. The step 1 test must give identical results, and Control's existing CI stays green.
3. **Differential validator test.** Run the library and `toolgate validate <file>` over the same fixtures. Record every case where they disagree. The library is authoritative (design §6, "Validators"). The CLI goes into the helper only as a reject-only extra check, and only for the cases where it rejects something the library accepts. If there are none, leave it out and say so in the PR.
4. **`marketplace/` skeleton:**
   - `README.md`, `LICENSE`, `.gitignore`, `.editorconfig`;
   - `TOOLGATE_SRC` and the path resolution in §1.4;
   - `scripts/isolation-check.sh` (§1.3), `scripts/init-signing-key.sh` (design §6.1);
   - `ci/run.sh`, `.github/workflows/marketplace.yml`, plus the root workflow shim.
   Add one line to each `apps/marketplace-*` scaffold README pointing at `marketplace/` (documentation, allowed by §1.2).
5. **`marketplace/docs/api.md`.** The public HTTP API, the helper's internal API (`/inspect`, `/sign`, token) and the release-record format, written from design 1.2 and frozen here. PR 2 implements it and PR 3 consumes it.
6. **`marketplace/helper/`**, a Java service that never executes package code. It runs as non-root with bounded resources, listens on an internal port only, and needs `MARKETPLACE_HELPER_TOKEN` on every request.
   - `POST /inspect`: validate; extract the listing, typed `requiredSettings`, the `compatibility` block (design §6.2 A); compute the API diff and SemVer check against the previous version Drupal passes in (§6.2 B). **Never signs.**
   - `POST /sign`: `{fileRef, packageDigest, authorizedPublisherSlug}`; re-validate, match the digest, match the id prefix to the slug, refuse reserved prefixes, then sign with `marketplace` = `MARKETPLACE_ID` (design §6, §6.1).
   - `GET /keys`.
   - It refuses to start without a signing key, and the key is never generated automatically.
7. **`marketplace/seed/`**: the seed image and `expected-packages.json` (§3.2 steps 1 and 2).
8. **`log-insights`**: recover the source, see §3.2 step 7.

**Done when:**
- the regression baseline and differential test results are in the PR body;
- the helper's tests pass:
  - each malformed fixture is rejected;
  - a directory never reaches `toolgate validate`;
  - `/inspect` returns no signature;
  - `/sign` without the token, with a mismatched slug, a reserved prefix or a changed digest is refused;
  - a signature verifies with the published key, and a tampered record fails;
- a test runs `/inspect` and `/sign` over **every** package the seed image produces and compares the result with `expected-packages.json` (§3.2);
- `isolation-check.sh` passes in both layouts (§1.4).

### PR 2: Drupal (website, public API, seeding, deployment)

Branch `marketplace/pr2-drupal`. Needs PR 1 merged. Contains:
- `marketplace/drupal/`: Composer project and module `toolgate_marketplace`, covering:
  - accounts, and publisher profiles with an **immutable slug** chosen at creation (no UI or API path changes it) and reserved names;
  - upload: `/inspect`, then the ownership and uniqueness checks from the **session's** publisher, then `/sign` (design §5.2);
  - the signed `VALIDATED` draft, listing save, `AVAILABLE`/`DELISTED`, admin takedown;
  - the public pages, and the public API and keys file exactly as `docs/api.md`;
  - downloads: `max-age=300, must-revalidate`, delisted downloads `410 Gone` and a proxy cache purge on delist;
  - the `compatibility` block and `apiChange` per version (design §6.2);
  - an "About signing" page with the key fingerprint;
- `drush toolgate-marketplace:seed` and the entrypoint that runs it (§3.2 steps 3 to 6);
- `marketplace/deploy/`: `compose.yaml` (drupal, db, helper, seed, proxy, and a one-shot `init-key` profile), `.env.example` with `MARKETPLACE_BASE_URL=https://olo-labs.duckdns.org` and `MARKETPLACE_ID=olo-labs-marketplace`, a `Caddyfile`, and `scripts/compose.sh` (§1.4);
- `scripts/split-simulation.sh` (§1.3).

**Done when:**
- after `scripts/compose.sh --profile init-key run --rm init-key` (once) and `scripts/compose.sh up -d` on a clean machine, every package in `expected-packages.json` is AVAILABLE and signed, and a restart changes nothing (§3.3);
- the Drupal tests from design §12 pass, including:
  - ownership from the session only;
  - the slug can't be changed;
  - XSS/Markdown;
  - private drafts not downloadable;
  - `410` after delist;
  - no private fields in the API;
- the split simulation builds and runs the stack from a standalone copy.

### PR 3: ToolGate admin (Control import and console)

Branch `marketplace/pr3-toolgate-admin`. Needs PR 2 merged; its stack is the end-to-end test target. Only ToolGate-side code (§2). Inside `marketplace/` it may only add an end-to-end job to `marketplace/ci/run.sh`. Contains:
- Control settings `TOOLGATE_CONTROL_MARKETPLACE_ENABLED`, `_ID`, `_BASE_URL` and `_KEYS` (environment-owned), with Helm and Quickstart wiring. Control logs and shows each pinned key's fingerprint and never fetches a key to trust it;
- Control endpoints for catalog, detail, the media proxy and import. Import runs **every** step of design §8.3, "Import verification":
  - not delisted;
  - a known `schema`;
  - a key pinned for this marketplace id, and a valid signature;
  - `marketplace`, `packageId`, `version` and `publisherSlug` matching the request, the detail record and the validated manifest;
  - `archiveSha256`, `packageDigest` and `descriptorSetDigest` matching what Control computes;
  - then the existing upload under Super Admin, audited with marketplace id, package id, version, publisher slug and `keyId`;
- compatibility checks (design §6.2 A) in Control's **existing** upload and deploy path, so they cover manual uploads too. The console marks each Marketplace version compatible or not, with the reason, and offers Install for the newest compatible version;
- OpenAPI in `control-v1.yaml` and regenerated bindings;
- the console's Marketplace view on the Server packages page: catalog, detail, publisher panel, images via the proxy with the CSP unchanged, Install, and the confirmation text from design §8.1.

**Done when:**
- unit tests cover a negative case for **each** import verification step, plus a non-allowlisted origin or redirect, an oversize package, and each incompatibility in design §6.2 A;
- an end-to-end test against the PR 2 stack imports `examples.notify` and `examples.health` into Quickstart, deploys them, enables and grants them, and calls them through `/mcp`;
- `control.yml`, `quickstart.yml`, `preflight.yml` and the admin-ui tests are green.

## 1. Isolation: the Marketplace must be liftable into its own repository

The Marketplace will soon move into a new repository, with `olo-toolgate` added to it as a git submodule at `toolgate/`. Build it so that move is a `git mv` plus a one-line change to `TOOLGATE_SRC`, with no code edits.

### 1.1 Layout

```text
marketplace/
  README.md                 # what it is, how to run it, how it will be split out
  TOOLGATE_SRC              # one line, relative to marketplace/: ".." here, "toolgate" after the split
  drupal/                   # Composer project, custom module toolgate_marketplace, theme, config/sync
  helper/                   # Java validation + signing service, its own Gradle build and wrapper
  seed/                     # seed image, expected-packages.json
  deploy/
    compose.yaml            # drupal, db, helper, seed, proxy, init-key profile
    .env.example
    Caddyfile
  scripts/                  # toolgate-dir.sh, compose.sh, init-signing-key.sh, isolation-check.sh, split-simulation.sh
  ci/run.sh                 # everything CI runs for the Marketplace
  docs/                     # design, prompt and plans (already committed); api.md (PR 1), deployment.md (PR 2)
  .github/workflows/marketplace.yml   # the real workflow; it becomes active after the split
```

- Do **not** put new Marketplace code in `apps/marketplace-drupal` or `apps/marketplace-worker`. Leave those scaffolds as they are, apart from the one README line from PR 1 step 4.
- Do not register anything under `marketplace/` in the root `settings.gradle.kts`, `Cargo.toml`, `package.json` or `Makefile`.

### 1.2 What is forbidden and what is allowed

The rule is about **dependencies**, not mentions.

**Forbidden in both directions**, except through the resolved ToolGate path (§1.4):
- **ToolGate → `marketplace/`:**
  - any build, source or package dependency: Gradle `include`/`includeBuild`, Cargo, npm or Composer workspace or path entries, source imports, Docker build contexts or `COPY` sources, script `source`/exec paths, test fixtures read from `marketplace/`;
  - any generated binding or schema that ToolGate code consumes from there.
- **`marketplace/` → ToolGate:** any path to ToolGate files that doesn't go through the resolved ToolGate path: a literal `../`, `../../`, `apps/...`, and so on.

**Allowed:**
- documentation links and prose in either direction, such as the scaffold README lines and links from `docs/`;
- ToolGate (PR 3) integrating with the Marketplace **over its public HTTP API** as specified in `api.md`. That is a network contract, not a file dependency;
- the root workflow shim `.github/workflows/marketplace.yml`, path-filtered to `marketplace/**`, whose only step runs `marketplace/ci/run.sh`.

Other rules:
- **The helper depends on ToolGate as a library, never by copying code:** `backend-libs/package-archive`, pulled in with a Gradle composite build of the resolved ToolGate path.
- Drupal reaches the helper only over HTTP. It may use the generated PHP bindings from `<TOOLGATE_DIR>/packages/contracts/php` through a Composer path repository resolved from the same path.
- `marketplace/` has its own `LICENSE` (Apache-2.0), `.gitignore`, `.editorconfig` and `README.md`. It doesn't rely on root dotfiles.

### 1.3 Proving it

- `scripts/isolation-check.sh` parses build and package files rather than grepping prose:
  - root and ToolGate Gradle settings and build files, `Cargo.toml`, `package.json` workspaces, Composer files, Dockerfiles and compose files, and shell scripts' `source`/exec and `cd` targets;
  - it fails on any forbidden dependency from §1.2;
  - Markdown, comments and HTTP URLs are ignored;
  - it has its own small test cases: a forbidden `includeBuild` and a forbidden `COPY ../apps` must fail; a README link and an HTTP base URL must pass.
- `scripts/split-simulation.sh` does a trial split:
  1. copies `marketplace/` alone into an empty temporary directory;
  2. adds this commit of `olo-toolgate` there as a real git submodule at `toolgate/`;
  3. writes `toolgate` into `TOOLGATE_SRC`;
  4. runs `isolation-check.sh`, the helper's tests, `scripts/compose.sh build` and a smoke bring-up.
- `ci/run.sh` runs the isolation check and the helper tests in the in-repo layout, and then the split simulation.

### 1.4 One resolved ToolGate location, pinned

- `marketplace/TOOLGATE_SRC` holds one path **relative to the `marketplace/` directory**: `..` in this repo, `toolgate` after the split.
- `scripts/toolgate-dir.sh` is the **only** resolver. It reads that file, resolves it to an absolute, symlink-free path, checks that it is a ToolGate checkout (it must contain `VERSION`, `packages/contracts/VERSION` and `backend-libs/package-archive`), and prints it as `TOOLGATE_DIR`.
  - Every consumer uses `TOOLGATE_DIR` and nothing else.
  - **Scripts** source the resolver.
  - **Gradle:** `helper/settings.gradle.kts` runs the resolver, or reads `TOOLGATE_DIR` from the environment that `ci/run.sh` and `compose.sh` set, and calls `includeBuild(TOOLGATE_DIR)`.
  - **Docker Compose** is only ever run through `scripts/compose.sh`. It exports the absolute `TOOLGATE_DIR` and `MARKETPLACE_DIR`, then runs `docker compose -f "$MARKETPLACE_DIR/deploy/compose.yaml" --project-directory "$MARKETPLACE_DIR/deploy" "$@"`. Build contexts in `compose.yaml` are `${TOOLGATE_DIR}` (seed, helper) and `${MARKETPLACE_DIR}/drupal`.
  - With absolute paths, nothing depends on Compose resolving relative paths against the compose file's directory. `compose.yaml` has no relative path that leaves `deploy/`.
- **Pinned revision:**
  - In this repo the ToolGate revision is the same commit as the Marketplace code.
  - After the split it is the submodule commit recorded by git; `TOOLGATE_SRC` never names a branch.
  - Images built by `compose.sh` get the label `io.ololabs.toolgate.revision=<git -C $TOOLGATE_DIR rev-parse HEAD>`, and the helper reports it at `GET /keys` and in its startup log.
  - The split simulation uses exactly the commit under test.
- Both layouts are built in CI (§1.3), so a path that works in only one of them fails the PR.

## 2. ToolGate-side changes (normal places in this repo)

These stay in ToolGate after the split. Their only link to the Marketplace is its public HTTP API.

| Change | Where |
|---|---|
| Extract `PackageArchive` into a library used by Control, with the regression baseline first | `backend-libs/package-archive`, `tests/fixtures/packages/` (PR 1) |
| Settings `TOOLGATE_CONTROL_MARKETPLACE_ENABLED`, `_ID`, `_BASE_URL`, `_KEYS`, environment-owned | Control configuration, Helm values and schema, Quickstart compose (PR 3) |
| Catalog, detail, media proxy and import with the full verification list | Control adapter and application layer; OpenAPI in `packages/contracts/openapi/control-v1.yaml`; regenerated bindings (PR 3) |
| Compatibility checks in the existing upload and deploy path | Control (PR 3) |
| Marketplace view and Install on the Server packages page | `apps/admin-ui/src/` (PR 3) |

## 3. Seed every example package at bring-up

### 3.1 What

On the first bring-up, every Java example package under `<TOOLGATE_DIR>/examples/java-tools/*` and `<TOOLGATE_DIR>/examples/mocked-tools/*` appears on the Marketplace as **AVAILABLE**.
- Each is published by the reserved publisher `examples`, owned by a system account "OLO Labs".
- Each goes through the **same `/inspect` → ownership check → `/sign` path** as a user upload. There is no shortcut and there are no unsigned rows.
- The set of packages is **discovered**, not hard-coded, and checked against an explicit expected list.

### 3.2 How

1. **Seed image** (`marketplace/seed/Dockerfile`), with build context `${TOOLGATE_DIR}`. It's built once by `compose.sh build`, not at every start.
   - Builder stage: JDK 17 and Rust. It runs the same steps as `examples/run-java-examples.sh`: `./gradlew publishJavaSdkLocally`, `cargo build -p toolgate-cli`, then in both example builds `./gradlew -PtoolgateSdkRepo=<root>/build/sdk-repo toolgatePackage`, then `toolgate verify` on each result.
   - **Discovery:** every subfolder of the two example builds that has a `build.gradle.kts` is an expected package source. The image writes `/seed/seed-manifest.json` with one entry per discovered folder: folder, package file or `null` if the build produced none, package id and version from the manifest, README text, and category (`java-tools` → "Operations", `mocked-tools` → "Demo (mocked data)").
   - Final stage: only `/seed`.
2. **`marketplace/seed/expected-packages.json`** (committed). The explicit list of package ids that must be published, 18 today, one per example folder.
   - Adding an example means adding its id.
   - Removing an id needs a reason in the commit message, and for `examples.logs` (log-insights) Rahul's approval.
   - PR 1's test fails if discovery and this list differ in either direction, or if any listed id fails `/inspect` or `/sign`.
3. **`seed` compose service.** One-shot: it copies `/seed` into a named volume and exits 0. Drupal `depends_on` it with `service_completed_successfully`.
4. **Drupal seed command** `drush toolgate-marketplace:seed /seed`. The entrypoint runs it after site install and config import when `MARKETPLACE_SEED_EXAMPLES=true` (the default; production can turn it off). For each manifest entry it:
   - ensures the system account and the `examples` publisher exist. Profile: display name "ToolGate Examples", organization "OLO Labs", website `https://github.com/olo-labs/olo-toolgate/tree/main/examples`;
   - runs the normal upload path: `/inspect`, ownership and uniqueness for the `examples` publisher, `/sign`;
   - fills the listing:
     - title: the manifest name;
     - summary: the README's first paragraph;
     - description: the README as sanitized Markdown;
     - category: as above;
     - tags: the tool-name prefixes plus `example`, and `mocked` for mocked-tools;
     - license: `Apache-2.0`;
     - repository URL: the example's GitHub folder;
   - marks the version AVAILABLE.
5. **Idempotent.**
   - The same id, version and `packageDigest` is skipped.
   - The same id and version with a **different** digest is an error and the row is left as it is.
   - A newer version is published as a new version.
6. **Degraded, never silent.**
   - At runtime, a package that fails is logged with its reason and the others continue.
   - The command compares the published set with `expected-packages.json` and writes a seed status: `complete` or `degraded`, with the missing ids and reasons.
   - A `degraded` status is shown:
     - as a banner on the admin dashboard;
     - on an admin-only seed status page;
     - as `"seed": "degraded"` in the internal health endpoint the proxy and CI read.
   - The command exits non-zero when the helper is unreachable. With `MARKETPLACE_SEED_STRICT=true`, which CI and release builds set, it also exits non-zero on `degraded`.
7. **`log-insights`: recover, never re-create.**
   - Known fact: commit `4164c0d` added only its README, build files and `toolgate.dev.example.json`.
   - Confirm the cause before changing anything. Run `git check-ignore -v` on the expected source path, and check whether the root `.gitignore` rule `logs/` matches it.
   - Restore the source **only** from a confirmed original: the checkout PR #89 was built from (the author's local `olo-toolgate` checkout or that session's workspace), with tests that pass and tools matching the PR #89 description (`logs.search`, `logs.levels`, `logs.errors`, `logs.tail`). Then narrow the ignore rule so it can't recur, and add a CI check that every example folder with a `build.gradle.kts` has sources under `src/main`.
   - **Never write a replacement implementation.** If the original can't be found, stop and report it in the PR. `examples.logs` stays in `expected-packages.json`, so the release gate stays red until Rahul decides.

### 3.3 Acceptance for seeding

- On a clean machine, `init-key` once and then `compose.sh up -d`: the seed status is `complete`, the public catalog lists exactly the ids in `expected-packages.json` as AVAILABLE, each with a `.release.json` that verifies with the published key, and the homepage shows them under the two categories.
- Restarting the stack changes nothing: same rows, same digests, no duplicates, and the key unchanged.
- A ToolGate Quickstart configured with `TOOLGATE_CONTROL_MARKETPLACE_ID`, `_BASE_URL` and the pinned key (fingerprint checked) imports `examples.notify` and `examples.health`, deploys them and calls them through `/mcp`.
- **Release gate:** `ci/run.sh` with `MARKETPLACE_SEED_STRICT=true` fails when any expected package is missing.

## 4. Everything else

Implement the rest of design 1.2 as written:
- publisher profiles with an immutable slug;
- upload, `/inspect` and `/sign`, the signed `VALIDATED` draft, listing save, `AVAILABLE`/`DELISTED`;
- the public API, caching and `410` rules, the keys file and fingerprint;
- compatibility;
- the security baseline, error handling and tests;
- the phased order in §13.

Key handling follows design §6.1:
- the key is created only by the explicit `init-key` step, which refuses to overwrite;
- it is stored in a secret volume mounted only into the helper, outside Drupal's volumes and backups, and never in Git;
- it has a separate documented backup procedure.

`MARKETPLACE_BASE_URL` (`https://olo-labs.duckdns.org` today) can change without touching signatures, because releases sign `MARKETPLACE_ID`.

## 5. Deliverable

- Exactly three PRs as in §0.
- Each PR body states:
  - every interface that differed from design 1.2;
  - the regression-baseline and differential-validator results (PR 1);
  - what `split-simulation.sh` printed (PR 2);
  - the seed status from a clean bring-up (PR 2).
- `ci/run.sh` (both layouts), the isolation check, Control's and the console's existing CI, and the new tests all pass before a PR is marked ready.
