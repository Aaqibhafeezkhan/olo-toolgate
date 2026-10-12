# Implementation prompt: ToolGate Marketplace v1

Give this whole file to the implementing agent. The normative design is [design.md](design.md) (version 1.1). This prompt adds three things: **where the code lives**, **how it stays separable**, and **seeding the example packages at bring-up**. Where this prompt and design.md differ, this prompt wins.

---

## Task

Build ToolGate Marketplace v1 as specified in `design.md` 1.1, in the repository `olo-labs/olo-toolgate`, delivered as the three PRs in §0. The work has three parts:
1. the Marketplace itself (Drupal, helper, seed, deployment) in **one isolated folder**;
2. the small ToolGate-side changes in their normal places;
3. seeding every example package as published at container bring-up.

Before writing code, read:
- `design.md` 1.1 and `design-review.md`;
- `tool-sdk/cli/src/main.rs` and `package.rs`;
- Control's `PackageArchive.java`, `ServerPackages.java`, `PackageResource.java`, `BodyLimit.java` and `ConsoleAssets.java`;
- the v2 `package` and `descriptor` schemas under `packages/contracts/schemas/v2/`;
- `examples/README.md` and `examples/run-java-examples.sh`.

Confirm every interface you rely on. If something differs from the design, adapt to the code and say so in the PR.

## 0. Delivery: exactly three pull requests

The work ships as **three PRs, in this order**. Each PR stands on its own: it builds, its tests pass, and it leaves `main` working. Sections 1 to 4 below are the detail each PR refers to.

### PR 1: Helper (validation, signing, example packages)

Branch `marketplace/pr1-helper`. Contains:
- the `marketplace/` skeleton:
  - `README.md`, `TOOLGATE_SRC` (`..`), `LICENSE`, `.gitignore`, `.editorconfig`;
  - `scripts/isolation-check.sh` and `scripts/gen-signing-key.sh`;
  - `ci/run.sh`, `.github/workflows/marketplace.yml`;
  - the thin root workflow shim (§1);
- `marketplace/docs/api.md`: the Marketplace public HTTP API and the release-record format from design.md §6.1 and §7, frozen here so PR 2 implements it and PR 3 consumes it;
- `backend-libs/package-archive`: Control's `PackageArchive` extracted into a library, with Control switched to it and no behaviour change (§1.2 rule 3);
- `marketplace/helper/`: Java service with `POST /inspect` (validate, extract listing and typed `requiredSettings`, sign) and `GET /keys`. Runs as non-root with bounded resources, an internal port only, and never executes package code;
- `marketplace/seed/`: the seed image that builds every example package and writes `seed-manifest.json` (§3.2 step 1);
- the `log-insights` source restored and the `.gitignore` rule narrowed (§3.2 step 6).

**Done when:**
- the helper's tests pass: valid package, each malformed case from design.md §12, a directory never reaching `toolgate validate`, a Control-only rule (one jar per Java tool), a signature that verifies with the published key, and a tampered record that fails;
- a test runs the helper over **every** package the seed image produces and every one validates and signs (18 today);
- Control's existing CI is green after the extraction;
- `isolation-check.sh` passes.

### PR 2: Drupal (website, public API, seeding, deployment)

Branch `marketplace/pr2-drupal`. Needs PR 1 merged. Contains:
- `marketplace/drupal/`: Composer project and module `toolgate_marketplace`. It covers accounts, publisher profiles with prefix/slug ownership and reserved names, upload calling the helper, the signed `VALIDATED` draft, listing save, `AVAILABLE`/`DELISTED`, admin takedown, the public pages, and the public API and keys file exactly as `docs/api.md`;
- `drush toolgate-marketplace:seed` and the entrypoint that runs it (§3.2 steps 2 to 5);
- `marketplace/deploy/`: `compose.yaml` (drupal, db, helper, seed, proxy), `.env.example` with `MARKETPLACE_BASE_URL=https://olo-labs.duckdns.org`, and a `Caddyfile`;
- `scripts/split-simulation.sh` (§1.3).

**Done when:**
- a clean `docker compose up -d` shows all example packages AVAILABLE and signed, and a restart changes nothing (§3.3);
- the Drupal tests from design.md §12 pass (ownership, XSS/Markdown, private drafts not downloadable, no private fields in the API);
- the split simulation builds and runs the stack from a standalone copy.

### PR 3: ToolGate admin (Control import and console)

Branch `marketplace/pr3-toolgate-admin`. Needs PR 2 merged (its compose stack is the end-to-end test target). Only ToolGate-side code (§2); **nothing under `marketplace/` except** a new end-to-end job in `marketplace/ci/run.sh`, if needed. Contains:
- the Control settings `TOOLGATE_CONTROL_MARKETPLACE_ENABLED`, `_BASE_URL` and `_KEYS` (environment-owned), plus Helm and Quickstart wiring;
- Control endpoints for catalog, detail, the media proxy and import. The import follows design.md §8: same origin only, no unsafe redirects, size and time caps, signature check against pinned keys, `archiveSha256` and `packageDigest` checks, then the existing upload under Super Admin, audited with its source;
- OpenAPI in `control-v1.yaml` and regenerated bindings;
- the console's Marketplace view on the Server packages page: catalog, detail, publisher panel, images via the proxy with the CSP unchanged, Install, and the confirmation text from design.md §8.1.

**Done when:**
- unit tests cover forged signature, unknown key, digest mismatch, a non-allowlisted origin or redirect, an oversize package and a delisted version;
- an end-to-end test against the PR 2 stack imports `examples.notify` and `examples.health` into Quickstart, deploys them, enables and grants them, and calls them through `/mcp`;
- `control.yml`, `quickstart.yml`, `preflight.yml` and the admin-ui tests are green.

Every PR body records what differed from the design and the test evidence. No fourth PR: anything found late goes into the PR it belongs to.

## 1. Isolation: the Marketplace must be liftable into its own repository

The plan is to move the Marketplace into a new repository soon. `olo-toolgate` will then be added to that new repository as a git submodule. Build it so that move is a `git mv` plus one path change, with no code edits.

### 1.1 Layout

All Marketplace code goes under a single top-level folder, `marketplace/`:

```text
marketplace/
  README.md                 # what it is, how to run it, how it will be split out
  TOOLGATE_SRC              # one line: path to the ToolGate source tree. Here: ".."  After the split: "toolgate"
  drupal/                   # Composer project, custom module toolgate_marketplace, theme, config/sync
  helper/                   # Java validation + signing service, its own Gradle build and wrapper
  seed/                     # seed image: builds and collects the example packages; seed manifest
  deploy/
    compose.yaml            # drupal, db, helper, seed, proxy
    .env.example
    Caddyfile
  scripts/                  # gen-signing-key.sh, isolation-check.sh, split-simulation.sh
  ci/run.sh                 # everything CI runs for the Marketplace
  docs/                     # design.md, implementation-prompt.md and plans (already committed); api.md and deployment.md added by PR 1 and PR 2
  .github/workflows/marketplace.yml   # the real workflow; it becomes active after the split
```

- Do **not** put new Marketplace code in `apps/marketplace-drupal` or `apps/marketplace-worker`. Leave those scaffolds untouched; they belong to the longer-term modules 12 to 15. Add one line to each scaffold's README pointing at `marketplace/`.
- Do not register anything under `marketplace/` in the root `settings.gradle.kts`, `Cargo.toml`, `package.json` or `Makefile`.

### 1.2 Rules

1. **Nothing outside `marketplace/` refers to anything inside it**, with one exception: a thin root workflow `.github/workflows/marketplace.yml`. It is path-filtered to `marketplace/**`, and its only step runs `marketplace/ci/run.sh`.
2. **Everything inside `marketplace/` reaches ToolGate code only through `TOOLGATE_SRC`.** That covers Docker build contexts, Gradle composite builds and scripts. Never write `../apps/...` or `../../` anywhere. Compose reads it as `${TOOLGATE_SRC}` from `.env`, and scripts read the `TOOLGATE_SRC` file.
3. **The helper depends on ToolGate as a library, never by copying code.** Extract Control's package validation (`PackageArchive` and what it needs) into a small library inside ToolGate, `backend-libs/package-archive`. Control and the helper both use it, so their checks can never drift apart. The helper's own `settings.gradle.kts` pulls it in with `includeBuild(<TOOLGATE_SRC>)` and substitutes the module.
4. Drupal's PHP talks to the helper only over HTTP. It may use the generated PHP contract bindings from `<TOOLGATE_SRC>/packages/contracts/php` through a Composer path repository built from `TOOLGATE_SRC`.
5. `marketplace/` has its own `LICENSE` (Apache-2.0, as the repo), `.gitignore`, `.editorconfig` and `README.md`. It must not rely on root-level dotfiles.

### 1.3 Proving it

- `scripts/isolation-check.sh` fails if a file outside `marketplace/` (other than the root workflow shim) mentions `marketplace/`, or if a file inside uses a `../` path that doesn't come from `TOOLGATE_SRC`. Prose under `marketplace/docs/` is excluded from both checks.
- `scripts/split-simulation.sh` does a trial split:
  1. copies `marketplace/` alone into a temporary directory;
  2. clones this commit of `olo-toolgate` into `<tmp>/toolgate`, standing in for the submodule;
  3. writes `toolgate` into `TOOLGATE_SRC`;
  4. runs `docker compose build` and the helper's tests.
- `ci/run.sh` runs both scripts.

## 2. ToolGate-side changes (normal places in this repo)

These stay in ToolGate after the split. They depend only on the Marketplace's public HTTP API, never on files under `marketplace/`.

| Change | Where |
|---|---|
| Extract `PackageArchive` into a library used by Control | `backend-libs/package-archive`, Control imports it; no behaviour change, existing Control tests stay green |
| Settings `TOOLGATE_CONTROL_MARKETPLACE_ENABLED`, `_BASE_URL`, `_KEYS`, environment-owned | Control configuration, Helm values and schema, Quickstart compose |
| Catalog, detail and media proxy plus import endpoint (fetch, verify signature, check digests, existing upload, audit with source) | Control adapter and application layer; OpenAPI in `packages/contracts/openapi/control-v1.yaml`; regenerate bindings |
| Marketplace view and Install on the Server packages page | `apps/admin-ui/src/` |

## 3. Seed every example package at bring-up

### 3.1 What

On the first `docker compose up`, every Java example package in `<TOOLGATE_SRC>/examples/java-tools/*` and `<TOOLGATE_SRC>/examples/mocked-tools/*` appears on the Marketplace as **AVAILABLE**. That's the 18 packages from PR #89. Each is published by the reserved publisher `examples`, owned by a system account "OLO Labs". Each goes through the **same validate-and-sign path** as a user upload: no shortcut and no unsigned rows.

### 3.2 How

1. **Seed image** (`marketplace/seed/Dockerfile`). This is a multi-stage build with `${TOOLGATE_SRC}` as its context, done once at `docker compose build`, not at every start.
   - Builder stage: a JDK 17 and Rust toolchain. It runs the same steps as `examples/run-java-examples.sh`:
     - `./gradlew publishJavaSdkLocally`;
     - `cargo build -p toolgate-cli`;
     - in `examples/java-tools` and `examples/mocked-tools`, `./gradlew -PtoolgateSdkRepo=<root>/build/sdk-repo toolgatePackage`;
     - `toolgate verify` on each result.
   - Final stage (small): only `/seed/packages/*.tgpkg` plus `/seed/seed-manifest.json`. The manifest has one entry per package: file name, source folder, `README.md` text and category.
   - Category mapping: `java-tools` → "Operations"; `mocked-tools` → "Demo (mocked data)".
2. **`seed` compose service.** A one-shot container that copies `/seed` into a named volume and exits 0. Drupal `depends_on` it with `service_completed_successfully`.
3. **Drupal seed command** `drush toolgate-marketplace:seed /seed`. The Drupal entrypoint runs it after site install and config import when `MARKETPLACE_SEED_EXAMPLES=true`, which is the default; production can turn it off. For each package it:
   - ensures the system account and the `examples` publisher exist. Profile: display name "ToolGate Examples", organization "OLO Labs", website link to `https://github.com/olo-labs/olo-toolgate/tree/main/examples`;
   - sends the file through the normal helper call (validate + sign);
   - fills the listing:
     - title: the manifest's name;
     - summary: the first paragraph of the README;
     - description: the README as sanitized Markdown;
     - category: from the manifest;
     - tags: the tool-name prefixes plus `example`, and `mocked` for mocked-tools;
     - license: `Apache-2.0`;
     - repository URL: the example's GitHub folder;
   - marks the version AVAILABLE.
4. **Idempotent.** On every later start:
   - an existing id and version with the same `packageDigest` is skipped;
   - the same id and version with a **different** digest is reported as an error and left as it is, because published versions are immutable;
   - a newer version of an example is published as a new version.
5. **Failures don't block bring-up.** A package that fails validation is logged with its reason, and the others continue. The command prints a summary (`seeded / skipped / failed`) and writes it to a status page that only admins can see. It exits non-zero only if the helper is unreachable.
6. **Known gap.** `examples/java-tools/log-insights` has no Java source on main; most likely the root `.gitignore` rule `logs/` swallowed it. Fix that in this work: narrow the ignore rule and restore the source. If you can't restore it, the seed must report it as failed, never silently produce 17.

### 3.3 Acceptance for seeding

- After `docker compose up -d` on a clean machine, the public catalog API reports every example package as AVAILABLE, each with a verifiable `.release.json`, and the homepage shows them under the two categories. The count must equal the number of example folders with a `build.gradle.kts` in the two builds (18 today).
- Restarting the stack changes nothing (same rows, same digests, no duplicates).
- A ToolGate Quickstart pointed at the Marketplace can import `examples.notify` and `examples.health`, verify their signatures, deploy them and call them through `/mcp`.

## 4. Everything else

Implement the rest of `design.md` 1.1 as written:
- publisher profiles, upload, the signed `VALIDATED` draft, listing save, `AVAILABLE`/`DELISTED`;
- the public API and keys file;
- security baseline, error handling and tests;
- the phased order in §13.

Generate the signing key with `scripts/gen-signing-key.sh` into a Docker secret, never into Git. The base URL is `MARKETPLACE_BASE_URL=https://olo-labs.duckdns.org` and must work unchanged when it is switched to the production domain.

## 5. Deliverable

- Exactly three PRs as in §0.
- The PR body states every interface that differed from the design, what `split-simulation.sh` printed, and the seed summary from a clean bring-up.
- `ci/run.sh`, the isolation check, the split simulation, Control's and the console's existing CI, and the new tests all pass before the PR is marked ready.
