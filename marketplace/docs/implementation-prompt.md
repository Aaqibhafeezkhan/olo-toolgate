# Implementation prompt: ToolGate Marketplace v1

Give this whole file to the implementing agent.

**Normative design:** [design.md](design.md) **version 1.2, frozen at commit `18776bbe04df26eacc2c5026e1f4defb6389da3c`** on `olo-labs/olo-toolgate` (`marketplace/docs/design.md`). Read that exact revision (`git show 18776bbe:marketplace/docs/design.md`). A later edit to design.md doesn't apply unless this line is updated to its commit. The single wire contract between the three PRs is `marketplace/docs/api.md`, **already committed** with the docs. PR 1 implements it; any change to it needs a reason in the PR that makes it.

This prompt adds these to the design:
- where the code lives;
- how it stays separable;
- how builds resolve ToolGate;
- seeding the example packages at bring-up;
- the fully built, stateless OLO Orgs Drupal site with its module list (§6);
- publishing the two containers (§7).

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

### Ground rules for PR 1 and PR 2 (Rahul, 2026-10-12)

- **No change to existing code.** PR 1 and PR 2 only **add** new files under `marketplace/`.
- **One exception:** the image-publishing steps in the two existing release workflows (§7). They are added as new, separate steps at the **end** of the existing publish jobs, and no existing step changes.
- Everything that changes existing ToolGate code moves to **PR 3**: the `PackageArchive` library extraction, the Control and console work, the `log-insights` restore and its `.gitignore` fix.
- **No checks required to merge PR 1 and PR 2.** They don't wait on or depend on the existing CI checks. The Marketplace's own `ci/run.sh` is available to run, but it isn't a merge gate for these two PRs.
- PR 3 changes existing code and goes through the full existing checks.

### Already committed (non-code assets, with the docs PR)

These exist on `main` once the docs PR merges. Use them as they are; don't recreate them.
- `marketplace/README.md`, `LICENSE`, `.gitignore`, `.editorconfig`, `TOOLGATE_SRC` (`..`);
- `marketplace/docs/api.md` (the contract);
- `marketplace/drupal/modules.yml` (the module list, §6.3);
- `marketplace/drupal/web/themes/custom/olo_orgs/images/logo.png` (dark mark), `logo-light.png` (white mark for dark headers) and `favicon.svg`. The OLO branding comes from the console assets; both PNGs have **transparent backgrounds** and are trimmed;
- `marketplace/seed/expected-packages.json` (§3.2 step 2; `examples.logs` is in `knownMissing`);
- `marketplace/deploy/.env.example`.

### PR 1: Helper (container, validation, signing, example packages)

Branch `marketplace/pr1-helper`. New files only. Contains:
- **`marketplace/` scripts** (the README, licence and `TOOLGATE_SRC` already exist):
  - the path resolution in §1.4;
  - `scripts/isolation-check.sh` (§1.3), `scripts/init-signing-key.sh` (design §6.1);
  - `ci/run.sh`;
  - `ci/publish-images.sh` (§7), which publishes the helper image in this PR.
- **`marketplace/helper/`**, a stateless Java service and the **helper container**:
  - `Dockerfile` with build context `${TOOLGATE_DIR}`. A builder stage compiles the existing, unchanged `toolgate` CLI (`cargo build -p toolgate-cli --release`) and the helper.
  - The final image is a distroless or slim JRE, non-root, with a read-only root filesystem.
  - It never executes package code, has bounded resources and an internal port only, and needs `MARKETPLACE_HELPER_TOKEN` on every request.
  - It fetches upload bytes through Drupal's internal file reference (object storage, §6.2), so any replica can serve any request.
  - It has no disk state; the signing key comes from a secret mount.
  - `POST /inspect`: validate; extract the listing, typed `requiredSettings`, the `compatibility` block (design §6.2 A); compute the API diff and SemVer check against the previous version Drupal passes in (§6.2 B). **Never signs.**
  - `POST /sign`: `{fileRef, packageDigest, authorizedPublisherSlug}`; re-validate, match the digest, match the id prefix to the slug, refuse reserved prefixes, then sign with `marketplace` = `MARKETPLACE_ID` (design §6, §6.1).
  - `GET /keys`, `GET /health`.
  - It refuses to start without a signing key, and the key is never generated automatically.
  - **Validator in PR 1:** the unchanged Rust CLI, `toolgate validate` on a single regular file, never a directory. The helper also implements the identity, prefix and digest checks itself. Control's `PackageArchive` becomes the authoritative validator in PR 3, through the shared library (design §6, "Validators"). Until then, Control's own revalidation on import is the backstop.
- **`marketplace/seed/`**: the seed build stage (§3.2 step 1), checked against the committed `expected-packages.json`. Packages are built from the unchanged examples in `${TOOLGATE_DIR}`.
- **Publishing:** the helper image is added to the two existing release workflows (§7).

**Done when:**
- the helper's own tests pass:
  - each malformed package case from design §12 is rejected;
  - a directory never reaches `toolgate validate`;
  - `/inspect` returns no signature;
  - `/sign` without the token, with a mismatched slug, a reserved prefix or a changed digest is refused;
  - a signature verifies with the published key, and a tampered record fails;
- a test runs `/inspect` and `/sign` over every package the seed stage produces and compares the result with `expected-packages.json`. Ids listed under `knownMissing` (§3.2 step 2) are reported, not failed;
- `git diff --stat main` shows only new files under `marketplace/` plus the added steps in the two workflow files.

### PR 2: Drupal (fully built site container, public API, seeding, deployment)

Branch `marketplace/pr2-drupal`. Needs PR 1 merged. New files only, plus the Drupal image added to the same two publishing steps. Contains:
- **`marketplace/drupal/`**: the fully built **OLO Orgs** site as a stateless, horizontally scalable container (§6). It has:
  - module `toolgate_marketplace`;
  - accounts, and publisher profiles with an **immutable slug** chosen at creation and reserved names;
  - upload: `/inspect`, then the ownership and uniqueness checks from the **session's** publisher, then `/sign` (design §5.2);
  - the signed `VALIDATED` draft, listing save, `AVAILABLE`/`DELISTED`, admin takedown;
  - the public pages, and the public API and keys file exactly as `docs/api.md`;
  - downloads: `max-age=300, must-revalidate`, and `410 Gone` once delisted;
  - `compatibility` and `apiChange` per version (design §6.2);
  - an "About signing" page with the key fingerprint;
  - the module list input and its configured defaults (§6.3, §6.4).
- **Seeding:** `drush toolgate-marketplace:seed`, with the example packages baked into the Drupal image at `/opt/olo/seed`. It runs as part of the deploy job (§6.2), never inside web replicas (§3.2 steps 3 to 6).
- **`marketplace/deploy/`:**
  - `compose.yaml`: drupal (2 replicas), the deploy job, the cron job, helper (2 replicas), MariaDB, Redis, MinIO, Caddy, and the one-shot `init-key` profile;
  - `.env.example` with `MARKETPLACE_BASE_URL=https://olo-labs.duckdns.org` and `MARKETPLACE_ID=olo-labs-marketplace`;
  - a `Caddyfile`, `scripts/compose.sh` (§1.4), and a Helm chart `deploy/helm/olo-orgs-marketplace`.
- `scripts/split-simulation.sh` (§1.3).

**Done when:**
- on a clean machine, `init-key` runs once, then `scripts/compose.sh up -d`. After that:
  - every package in `expected-packages.json` except `knownMissing` is AVAILABLE and signed;
  - the seed status says `degraded` only for `knownMissing`;
  - a restart changes nothing;
- the statelessness test from §6.2 passes with two Drupal replicas;
- the Drupal tests from design §12 pass, including:
  - ownership from the session only;
  - the slug can't be changed;
  - XSS/Markdown;
  - private drafts not downloadable;
  - `410` after delist;
  - no private fields in the API;
- the split simulation builds and runs the stack from a standalone copy;
- `git diff --stat main` shows only new files under `marketplace/` plus the added workflow steps.

### PR 3: ToolGate admin (all existing-code changes)

Branch `marketplace/pr3-toolgate-admin`. Needs PR 2 merged; its stack is the end-to-end test target. This is the **only** PR that changes existing code, and it goes through the full existing checks. Work in this order:
1. **Regression baseline.** A fixture set under `tests/fixtures/packages/` (valid packages, each malformed case from design §12, Control-only rule cases such as a Java tool without exactly one jar). A Control test records today's accept/reject decision and digests for every fixture, run on the unchanged code.
2. **Extract** `PackageArchive` into `backend-libs/package-archive` and switch Control to it. The step 1 test must give identical results.
3. **Differential test** of the library against `toolgate validate` over the same fixtures. Then switch the helper to the library as its authoritative validator. Keep the CLI only as a reject-only extra check for the cases where it rejects something the library accepts, and list those cases. This is the only change PR 3 makes under `marketplace/`, apart from an end-to-end job in `ci/run.sh`.
4. **`log-insights`** recovery and `.gitignore` fix (§3.2 step 7), then move `examples.logs` from `knownMissing` to required.
5. **Control and console:**
   - settings `TOOLGATE_CONTROL_MARKETPLACE_ENABLED`, `_ID`, `_BASE_URL` and `_KEYS` (environment-owned), with Helm and Quickstart wiring. Control logs and shows each pinned key's fingerprint and never fetches a key to trust it;
   - endpoints for catalog, detail, the media proxy and import. Import runs **every** step of design §8.3, "Import verification":
     - not delisted;
     - a known `schema`;
     - a key pinned for this marketplace id, and a valid signature;
     - `marketplace`, `packageId`, `version` and `publisherSlug` matching the request, the detail record and the validated manifest;
     - `archiveSha256`, `packageDigest` and `descriptorSetDigest` matching what Control computes;
     - then the existing upload under Super Admin, audited with marketplace id, package id, version, publisher slug and `keyId`;
   - compatibility checks (design §6.2 A) in the **existing** upload and deploy path;
   - OpenAPI in `control-v1.yaml` and regenerated bindings;
   - the console's Marketplace view on the Server packages page: images via the proxy with the CSP unchanged, Install, and the confirmation text from design §8.1.

**Done when:**
- the regression baseline and differential results are in the PR body;
- unit tests cover a negative case for **each** import verification step, plus a non-allowlisted origin or redirect, an oversize package, and each incompatibility in design §6.2 A;
- an end-to-end test against the PR 2 stack imports `examples.notify` and `examples.health` into Quickstart, deploys them, enables and grants them, and calls them through `/mcp`;
- all existing checks are green: `control.yml`, `quickstart.yml`, `preflight.yml`, the admin-ui tests and `ci/run.sh` in both layouts, with `MARKETPLACE_SEED_STRICT=true`.

## 1. Isolation: the Marketplace must be liftable into its own repository

The Marketplace will soon move into a new repository, with `olo-toolgate` added to it as a git submodule at `toolgate/`. Build it so that move is a `git mv` plus a one-line change to `TOOLGATE_SRC`, with no code edits.

### 1.1 Layout

```text
marketplace/
  README.md                 # what it is, how to run it, how it will be split out
  TOOLGATE_SRC              # one line, relative to marketplace/: ".." here, "toolgate" after the split
  drupal/                   # Composer project, modules.yml (§6.3), custom module toolgate_marketplace, theme olo_orgs, config/sync, Dockerfile
  helper/                   # Java validation + signing service, its own Gradle build and wrapper
  seed/                     # seed build stage, expected-packages.json
  deploy/
    compose.yaml            # drupal x2, deploy job, cron, helper x2, MariaDB, Redis, MinIO, Caddy, init-key profile
    .env.example
    Caddyfile
    helm/olo-orgs-marketplace/   # Helm chart: Drupal Deployment + HPA + PDB, deploy Job, cron CronJob, helper Deployment + HPA
  scripts/                  # toolgate-dir.sh, compose.sh, init-signing-key.sh, isolation-check.sh, split-simulation.sh
  ci/run.sh                 # the Marketplace's own checks (not a merge gate for PR 1 and PR 2)
  ci/publish-images.sh      # builds and pushes the helper and Drupal images; called by the two release workflows (§7)
  docs/                     # design, prompt and plans (already committed); api.md (PR 1), deployment.md (PR 2)
```

- Do **not** put new Marketplace code in `apps/marketplace-drupal` or `apps/marketplace-worker`, and don't edit those scaffolds (no existing-code changes in PR 1 and PR 2).
- Do not register anything under `marketplace/` in the root `settings.gradle.kts`, `Cargo.toml`, `package.json` or `Makefile`.

### 1.2 What is forbidden and what is allowed

The rule is about **dependencies**, not mentions.

**Forbidden in both directions**, except through the resolved ToolGate path (§1.4):
- **ToolGate → `marketplace/`:**
  - any build, source or package dependency: Gradle `include`/`includeBuild`, Cargo, npm or Composer workspace or path entries, source imports, Docker build contexts or `COPY` sources, script `source`/exec paths, test fixtures read from `marketplace/`;
  - any generated binding or schema that ToolGate code consumes from there.
- **`marketplace/` → ToolGate:** any path to ToolGate files that doesn't go through the resolved ToolGate path: a literal `../`, `../../`, `apps/...`, and so on.

**Allowed:**
- documentation links and prose in either direction, such as links from `docs/`;
- ToolGate (PR 3) integrating with the Marketplace **over its public HTTP API** as specified in `api.md`. That is a network contract, not a file dependency;
- the added steps in the two existing release workflows that call `marketplace/ci/publish-images.sh` (§7). After the split, these move to the new repository's own workflows.

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
| Extract `PackageArchive` into a library used by Control, with the regression baseline first | `backend-libs/package-archive`, `tests/fixtures/packages/` (PR 3) |
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

1. **Seed build stage** (`marketplace/seed/Dockerfile.stage`, used as a stage of the Drupal image build with context `${TOOLGATE_DIR}`). It runs at image build time, not at every start. Its output is copied into the Drupal image at `/opt/olo/seed`.
   - Builder stage: JDK 17 and Rust. It runs the same steps as `examples/run-java-examples.sh`: `./gradlew publishJavaSdkLocally`, `cargo build -p toolgate-cli`, then in both example builds `./gradlew -PtoolgateSdkRepo=<root>/build/sdk-repo toolgatePackage`, then `toolgate verify` on each result.
   - **Discovery:** every subfolder of the two example builds that has a `build.gradle.kts` is an expected package source. The image writes `/seed/seed-manifest.json` with one entry per discovered folder: folder, package file or `null` if the build produced none, package id and version from the manifest, README text, and category (`java-tools` → "Operations", `mocked-tools` → "Demo (mocked data)").
   - Output: only `/seed` (packages plus `seed-manifest.json`).
2. **`marketplace/seed/expected-packages.json`** (already committed). The explicit list of package ids that must be published: 17 required plus `examples.logs` in `knownMissing`, one per example folder.
   - Adding an example means adding its id.
   - Removing an id needs a reason in the commit message, and for `examples.logs` (log-insights) Rahul's approval.
   - `expected-packages.json` also has a `knownMissing` list with a reason per id. Today that is `examples.logs` ("log-insights source missing on main; restored in PR 3").
   - PR 1's test fails if discovery and the required list differ, or if any required id fails `/inspect` or `/sign`. `knownMissing` ids are reported in the output and the seed status, never silently dropped.
   - PR 3 empties `knownMissing` once `log-insights` is restored. `MARKETPLACE_SEED_STRICT=true` treats any `knownMissing` entry as a failure, and the PR 3 and release gates set it.
3. **Where seeding runs.** In the deploy job (§6.2), after `drush deploy`. It never runs in the web replicas, so scaling out never triggers or races a seed.
4. **Drupal seed command** `drush toolgate-marketplace:seed /opt/olo/seed`. The deploy job runs it after site install and config import when `MARKETPLACE_SEED_EXAMPLES=true` (the default; production can turn it off). For each manifest entry it:
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
   - **Never write a replacement implementation.** If the original can't be found, stop and report it in PR 3. `examples.logs` stays in `knownMissing`, so the strict release gate stays red until Rahul decides.
   - This is PR 3 work, because it changes existing files.

### 3.3 Acceptance for seeding

- On a clean machine, `init-key` once and then `compose.sh up -d`: the seed status is `complete` (after PR 3; `degraded` only for `knownMissing` in PR 2), the public catalog lists exactly the ids in `expected-packages.json` as AVAILABLE, each with a `.release.json` that verifies with the published key, and the homepage shows them under the two categories.
- Restarting the stack changes nothing: same rows, same digests, no duplicates, and the key unchanged.
- A ToolGate Quickstart configured with `TOOLGATE_CONTROL_MARKETPLACE_ID`, `_BASE_URL` and the pinned key (fingerprint checked) imports `examples.notify` and `examples.health`, deploys them and calls them through `/mcp`.
- **Release gate (PR 3 onward):** `ci/run.sh` with `MARKETPLACE_SEED_STRICT=true` fails when any expected package is missing.

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

- Exactly three PRs as in §0. PR 1 and PR 2 add only new files (plus the workflow steps), and their merge doesn't wait on checks.
- Each PR body states:
  - every interface that differed from design 1.2;
  - the regression-baseline and differential-validator results (PR 3);
  - what `split-simulation.sh` printed (PR 2);
  - the seed status from a clean bring-up (PR 2).
- PR 3: `ci/run.sh` (both layouts, strict), the isolation check, Control's and the console's existing CI, and the new tests all pass before it is marked ready.

## 6. The OLO Orgs Drupal site: fully built, stateless, horizontally scalable

### 6.1 Image

`marketplace/drupal/Dockerfile` builds **one complete site image**, `olo-orgs-marketplace-drupal`. It contains:
- Drupal core and every module from `modules.yml`, installed by Composer at build time with a committed `composer.lock`;
- the custom module `toolgate_marketplace`;
- the theme `olo_orgs`;
- the exported configuration in `config/sync`;
- the seed packages (§3.2).

Nothing is downloaded or installed at runtime.

- Base: the official `drupal` image for the current Drupal 11 release on PHP 8.3, with Apache, **pinned by digest**. One container serves HTTP on port 8080, as non-root, with a read-only root filesystem; `/tmp` is the only writable path.
- Site name **OLO Orgs**, set in `config/sync/system.site.yml`.
- **Branding:**
  - the OLO logo (transparent PNG: `images/logo.png` on light backgrounds, `images/logo-light.png` on dark) and `favicon.svg` are already committed in `marketplace/drupal/web/themes/custom/olo_orgs/`, copied once from the console assets, so the split doesn't break them;
  - use them as the site logo and favicon;
  - the colour palette matches the ToolGate console.
- `settings.php` reads **everything** from the environment:
  - database, Redis, object storage, hash salt (from a secret), trusted host patterns built from `MARKETPLACE_BASE_URL`, reverse-proxy settings, helper URL and token, `MARKETPLACE_ID`, mail;
  - there is no environment-specific file in the image.

### 6.2 Stateless and horizontally scalable

| Concern | Where it lives | Never |
|---|---|---|
| Database | External MariaDB 10.11+ or PostgreSQL 16 (one per environment) | Local SQLite |
| Public and private files: uploads, signed packages, release records, avatars, screenshots, CSS/JS aggregates | S3-compatible object storage via the `s3fs` module (MinIO locally) | Container disk |
| Cache, cache tags, locks, flood | Redis via the `redis` module | Per-pod cache bins |
| Sessions | Database (core default), so any replica serves any request | Sticky sessions |
| Config | `config/sync` baked into the image, imported by the deploy job | UI-only changes; config edits in production are blocked by `config_readonly` |
| Install, updates, config import, seeding | A **deploy job**, run once per release: `drush site:install --existing-config` if the database is empty, otherwise `drush deploy`, then the seed | Run by web replicas at startup |
| Cron | A scheduled job running `drush cron`; `automated_cron` stays disabled | Poor-man's cron in requests |
| Health | `GET /health` checks DB, Redis and object storage, with no session | Loading the front page as a probe |

- Helm (`deploy/helm/olo-orgs-marketplace`):
  - Drupal: a Deployment with an HPA on CPU, min 2 replicas, a PodDisruptionBudget, and readiness and liveness on `/health`;
  - the deploy Job runs as a pre-upgrade hook, and cron is a CronJob;
  - the helper: a Deployment with an HPA, min 2 replicas, and a NetworkPolicy so only Drupal pods can reach it;
  - secrets come from Kubernetes Secrets, never from values files.
- **Statelessness test** (PR 2), on Compose with 2 Drupal replicas behind Caddy and no sticky sessions:
  1. log in through replica A and upload through replica B;
  2. read the listing and download through either replica;
  3. kill a replica mid-session and check that the user stays logged in;
  4. scale to 3 replicas and back with no data loss.

### 6.3 Module list as input

`marketplace/drupal/modules.yml` is the **single input** for which modules are in the site. Adding a module later means adding an entry, nothing else.

```yaml
# Each entry: Composer package and version constraint, the module machine names to enable, and why.
modules:
  - { package: drupal/admin_toolbar, version: "^3", enable: [admin_toolbar, admin_toolbar_tools], why: admin navigation }
```

- At **image build**, `scripts/build-modules.php` reads the list, runs `composer require` for each package (results pinned in `composer.lock`), and merges every `enable` name into `config/sync/core.extension.yml`. The deploy job's config import therefore enables them; a listed module can't be silently uninstalled by a later import.
- Contrib **themes** go in the same file with `type: theme`.
- A test fails the build if a module in `core.extension.yml` is missing from `modules.yml` plus core and custom modules, or the other way round.
- Each module's starting configuration lives in `config/sync` (§6.4), so a fresh deploy is fully configured.
- **Default list.** Check each entry for a stable Drupal 11 release when implementing. Drop any entry without one, and say so in the PR rather than pinning a dev release.

| Purpose | Modules |
|---|---|
| Front theme | `bootstrap5` (base theme) with the custom subtheme `olo_orgs` |
| Admin UX | `gin`, `gin_toolbar`, `admin_toolbar` |
| Stateless infrastructure | `s3fs`, `redis`, `config_readonly`, `health_check` |
| Catalog and search | `search_api`, `search_api_db`, `facets`, `better_exposed_filters`, `pathauto`, `token`, `field_group` |
| Content rendering | `markdown_easy` (safe Markdown for README and descriptions), `svg_image` |
| SEO and sharing | `metatag` (including Open Graph), `simple_sitemap`, `redirect` |
| Abuse and security | `honeypot`, `flood_control`, `seckit`, `password_policy` |
| Mail | `symfony_mailer_lite` |

Core modules enabled as well: `views`, `media`, `media_library`, `image`, `contact`, `path`, `block_content`, `responsive_image`, `big_pipe`, `dynamic_page_cache`, `page_cache`.

### 6.4 Configured for a good marketplace experience

All of the following ships in `config/sync`, so it works right after the first deploy.

- **Navigation:**
  - header: logo, OLO Orgs, Browse tools, Publishers, Docs, a **Publish a tool** button, and Sign in / My account;
  - footer: About signing, Terms, Privacy, Report abuse, Contact.
- **Home:**
  - a short hero with one sentence and a large search box;
  - category tiles (Operations, Demo (mocked data), and more as they're added);
  - "Newest tools" and "From verified OLO examples" card rows.
- **Tool cards:** logo or initials, title, one-line summary, publisher name and avatar, runtime, a "Signed" badge, and a compatibility hint ("Tool Host ≥ 0.10, Java 17").
- **Browse:**
  - a Search API view with facets for category, runtime, tags and publisher;
  - sorting by newest or name;
  - exposed filters that update without a full reload (better_exposed_filters with AJAX);
  - pager with 24 per page;
  - an empty state that suggests clearing filters.
- **Tool page:**
  - header with title, publisher, version picker, the **Download** button, and a **Use in ToolGate** box that copies the package id, version and digest;
  - tabs: Overview (README), Tools (each tool with its description and effect), Settings it needs (the typed `requiredSettings`), Versions (with `apiChange` and compatibility), Publisher;
  - a signature panel with key id, fingerprint and a link to About signing.
- **Publisher page:** avatar, display name, bio, links, and their tools as cards.
- **Publish flow:**
  - a four-step wizard with a visible step indicator: 1 Upload, 2 Checks (live result from `/inspect`), 3 Describe (form prefilled from the listing), 4 Publish;
  - clear error messages from the helper;
  - drafts resume from My packages.
- **My packages:** a table of the user's packages and versions, with status chips (Validated, Available, Delisted, Rejected) and actions.
- **Paths:** `/tools/{packageId}`, `/tools/{packageId}/{version}`, `/publishers/{slug}`, `/publish` (pathauto patterns).
- **Metadata:** metatags and Open Graph from the listing, a sitemap of tools and publishers, and canonical URLs from `MARKETPLACE_BASE_URL`.
- **Accessibility:** WCAG 2.2 AA (contrast, focus states, keyboard-only publish flow, alt text). Checked with axe in the PR 2 browser tests.
- **Responsive:** one column under 768 px, with cards stacking and facets in a slide-over panel.
- **Performance:** page cache and dynamic page cache on, CSS/JS aggregation into object storage, responsive images for logos and screenshots, and BigPipe for logged-in pages.

## 7. Publishing the containers

Two images: **`olo-toolgate-marketplace-helper`** (PR 1) and **`olo-orgs-marketplace-drupal`** (PR 2).

- `marketplace/ci/publish-images.sh` resolves `TOOLGATE_DIR` (§1.4) and builds both images with the image's ToolGate revision label. It pushes them to the same registry and namespace the existing release scripts use (`DOCKERHUB_NAMESPACE` with `DOCKERHUB_USERNAME`/`DOCKERHUB_TOKEN`, and GitHub Packages). It prints each image reference and digest as step outputs.
  - Tags: the development version from `VERSION` plus the short commit SHA, and `dev`. The same scheme as the existing development images.
  - In PR 1 it builds only the helper. PR 2 adds the Drupal image to the same script, so the workflows don't change again.
- **The two existing jobs**, with new steps appended at the end of each and no existing step modified:
  1. **Manual:** `.github/workflows/manual-development-release.yml`, job `publish` (`workflow_dispatch` on `main`). It gets the step "Publish Marketplace images" running `bash marketplace/ci/publish-images.sh`.
  2. **On merge:** `.github/workflows/development-release.yml`, job `publish` (called from `ci.yml` on push to `main`). It gets the same step, followed by `actions/attest-build-provenance` for each Marketplace image digest, as the job already does for the Control, Gateway, Quickstart and Device images.
- The new steps run **after** the existing publishing, so a Marketplace build failure can't stop ToolGate's own images from publishing. They report failure, but don't retry or skip anything else.
- After the split, these steps move to the new repository's workflows unchanged, apart from where they live.
