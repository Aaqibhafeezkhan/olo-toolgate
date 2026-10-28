# How to release ToolGate

Product releases use two manual GitHub Actions jobs. For a development package
from any branch, use [Manual development release](development-releases.md).

The product jobs are:

1. **Prepare release branch** creates a versioned source snapshot. It does not build or publish.
2. **Build and release binaries** builds/tests that snapshot, or publishes an existing verified build.

Release notes are generated during release into the release assets, using the
current product/contracts versions, `CHANGELOG.md` and optional human-contributor
fragments. Ordinary PRs leave tracked `RELEASE-NOTES.md` snapshots unchanged;
`tools/contracts/version.py` does not rewrite them. The **PR contribution policy**
check rejects generated-note edits and AI-assistant contributor attribution.
Configure `contribution-policy` as a required status check on protected branches.

Run both workflows with **Use workflow from: main**. The source branch is a separate input;
the release workflow definition always comes from main. Merge the workflow/tooling changes to
main before the first run. These jobs do not depend on `TOOLGATE_CI_ENABLED` or an `RC:` PR title.

## One version across the tool

Use `1.0.0-beta.42` everywhere: product, contracts, Java SDK, TypeScript/Rust/PHP distributions,
native clients, authoring CLI, Tool Host, installers, UI, images, Helm chart and GitHub Release.
No publisher derives a different development version or uses its Actions run number as the
release build number. Protocol/schema identifiers keep their compatibility meaning.

| Identity | Example |
|---|---|
| Base version | `1.0.0` |
| Allocated build | `42` |
| Channel | `Beta` |
| Product and package version | `1.0.0-beta.42` |
| Source branch | `main` or an existing `release/*` branch |
| Prepared release branch | `release/1.0.0-beta.42` |
| Final release tag | `v1.0.0-beta.42` |
| Windows installer / Chrome numeric version | `1.0.0.42` |
| macOS short version / numeric build | `1.0.0` / `1.0.42` |

Numeric platform fields are derived representations of the same release. The macOS build
encoding is `(build // 10000 + 1).((build % 10000) // 100).(build % 100)`, which stays within
Apple's numeric component limits. Build/core components are limited to 65535 for Windows/Chrome.

The build counter is shared by all source branches and channels for the same base version.
Preparing another `1.0.0` release increments the build: `.42`, `.43`, etc. It does **not**
increment the minor version. Enter `1.1.0` explicitly to begin that release series.
Allocated numbers are never reused, including failed or abandoned preparations.

`Beta` produces `1.0.0-beta.42`; `RC` produces `1.0.0-rc.43`. `Stable` produces `1.0.0`,
with its allocated numeric build retained in metadata. A stable package version can be
prepared/released only once; choose a new base version for subsequent stable changes.

## One-time repository setup

- Enable Actions read/write permissions and allow the repository to publish GitHub Packages.
- Create the **production-release** environment. Restrict it to workflow executions on
  `main`; configure reviewers if your release policy requires them. Both workflows run on
  main even when the code being released comes from another allowed source branch.
- GitHub npm, Maven and GHCR use the built-in `GITHUB_TOKEN` by default. Existing package
  access settings must grant this repository access.
- For a custom Maven repository, set environment variable `MAVEN_REPOSITORY_URL` and
  secrets `MAVEN_USERNAME` and `MAVEN_PASSWORD`. Credentials must be explicit for custom
  repositories; the GitHub token is never used as a fallback there.
- For Maven Central, set `MAVEN_REPOSITORY_URL` to
  `https://central.sonatype.com/api/v1/publisher/upload`, configure the Portal username/password
  plus `MAVEN_GPG_PRIVATE_KEY` and `MAVEN_GPG_PASSPHRASE`. See [signing setup](../../tools/release/README.md).
- Protect prepared `release/*` branches and the `prepared/*`, `release-builds/*`,
  `release-runs/*`, `published/*`, `central-deployments/*`, and `v*` tags against updates/deletion.
  Permit the release automation to create new refs. These refs store immutable preparation,
  counter, build-run and publication records; do not delete them to reset a counter or retry.
- Allow 90-day Actions artifact retention, or adjust the two workflows to the repository's
  supported limit. Keep sufficient storage for five container archives and six native platforms.

No Docker Hub credential is needed for this release path. Versioned images go to GHCR.
Existing development workflows remain separate and retain their own credentials/behavior.

## Job 1: prepare the release branch

Open **Actions → Prepare release branch → Run workflow**, select workflow branch **main**, and enter:

| Input | Value |
|---|---|
| `source_branch` | `main`, or an existing `release/*` branch |
| `source_commit` | `latest`, or a full 40-character commit SHA reachable from that branch |
| `base_version` | `1.0.0` |
| `channel` | `Beta` |

`latest` is resolved once. A retry of the same Actions preparation run retains the original
commit and build number even if main has advanced. To prepare another release, start a new run.
Branch choices are validated text inputs; GitHub's custom choice inputs do not dynamically
enumerate branches.

CLI equivalent:

```sh
gh workflow run create-release-branch.yml --ref main \
  -f source_branch=main -f source_commit=latest -f base_version=1.0.0 -f channel=Beta
```

For an older commit or a previous release branch:

```sh
gh workflow run create-release-branch.yml --ref main \
  -f source_branch=release/1.0.0-beta.42 \
  -f source_commit=0123456789abcdef0123456789abcdef01234567 \
  -f base_version=1.0.0 -f channel=Beta
```

Replace the example SHA with a real ancestor of the selected branch.

The summary reports the resulting branch, version, source commit and prepared commit.
The branch contains `release.json`, synchronized versions/lockfiles/generated bindings,
release notes and the source version's existing initial-configuration bundle mapping.
Preparation carries current standalone packaging adapters into older source trees; the
prepared commit records those changes. It does not merge unrelated changes from main.

An immutable `prepared/<version>` tag records the prepared commit. Do not add fixes to
this branch: prepare a new release from a source branch containing the fixes.

## Job 2: build, inspect, and publish

Open **Actions → Build and release binaries → Run workflow**, select the prepared workflow branch **release/<version>**:

| Input | First build |
|---|---|
| Workflow branch | Branch from Job 1, e.g. `release/1.0.0-beta.42` |
| `mode` | `build-only` |
| `build_run_id` | Leave empty |

```sh
gh workflow run build-release.yml --ref release/1.0.0-beta.42 \
  -f mode=build-only
```

The workflow runs on the exact prepared release branch commit. It runs CI preflight,
blocking coverage checks and the Sonar quality gate when configured, foundation tests/scans, all six native platform builds and
installer checks, and production container validation. Build/test stages have no publishing
credentials. Foundation and native builds reuse the existing workflows; publication does
not rebuild Java libraries or containers.

After success, download the **release-bundle** artifact. It contains checksummed native
installers/distributions, Java libraries and SDK JARs, Rust `.crate`, TypeScript tarball,
PHP ZIP, raw contracts, UI bundle, Helm chart, licenses, compatibility metadata, SBOMs,
the five tested image archives and `build-manifest.json`. Native distributions also carry
the authoring CLI and Tool Host for their platform. Windows ZIPs remain internal build inputs;
the public Windows installation artifact is the combined `.setup.exe`.

Record the original build Actions run ID from the summary or run URL. To publish those
exact bytes, run **the same second workflow** again:

```sh
gh workflow run build-release.yml --ref release/1.0.0-beta.42 \
  -f mode=build-and-publish \
  -f build_run_id=123456789
```

Replace `123456789` with the original build run ID. Build/test jobs are skipped in reuse
mode, and the original run's successful gates, immutable build reservation, source/version
identity and complete checksums are verified before publication.

For a single build-and-publish run, choose `build-and-publish` and leave `build_run_id` empty.
Publication waits for all checks and any configured production environment approval.

## Published outputs

| Output | Destination |
|---|---|
| Contracts JAR, sources, Javadoc, POM and Gradle module | Configured Maven repository; default GitHub Maven Packages |
| `@olo-labs/toolgate-contracts` | GitHub npm Packages |
| Control, Gateway, Quickstart, device, secured device | `ghcr.io/olo-labs/<image>:1.0.0-beta.42` |
| Helm chart | `oci://ghcr.io/olo-labs/charts/olo-toolgate:1.0.0-beta.42` |
| Installers, distributions, SDK JARs, SBOMs and manifests | GitHub Release `v1.0.0-beta.42` |

The owner follows the repository owner if this repository is forked. Public npm, crates.io,
Packagist and Java SDK registry publication are not configured by this pipeline; their checked
distributions are available as release assets. Installers remain unsigned/not notarized,
as in the existing native packaging workflow. Provenance does not claim OS code signing.

All required registries and attestations must succeed before the GitHub release becomes
public. Beta/RC releases are prereleases. npm publication initially uses an immutable build
tag; the `beta`, `rc` or stable `latest` npm alias advances only after finalization and never
moves backwards. Container `dev`/`latest` aliases are not changed. GitHub stable releases
also leave the repository's `latest` selection unchanged.

`build-manifest.json` records the prepared source commit, original build run and trusted
main controller commit. Attestations identify the controller workflow; this manifest binds
its artifacts to the separately selected source tree. `registry-publication.json` records
registry coordinates and container digests. Check these with the included checksums.

## Retry and recovery

- **Preparation interrupted:** use **Re-run failed jobs** on the same preparation run. Its
  reservation pins the source/build and resumes branch/tag creation without force-pushing.
- **Build/test failure before collection:** re-run failed jobs in the original build run.
  If source changes are needed, fix the source and prepare a new build number.
- **Build succeeded; publishing failed:** rerun the failed publishing job, or run Job 2 with
  the original `build_run_id`. The same bundle is downloaded; no binaries are rebuilt.
- **Some registries succeeded:** matching images/packages/charts are verified and reused;
  missing outputs are uploaded. Conflicting bytes stop publication and are never replaced.
- **Central validation/publishing timeout:** the deployment ID is recorded in the summary
  and an immutable tag; retries poll that deployment instead of uploading a second one.
  If the upload response was lost before an ID was received, inspect the Portal before
  retrying. A partially visible Central version must finish propagating before reuse.
- **Prepared branch changed:** publication fails. Prepare a new release; do not move the
  preparation tag or rewrite an existing release version.
- **Artifacts expired/deleted:** the version cannot be rebuilt for publication. Prepare a
  new build number. Preserve/download bundles before the retention period expires.

Cross-registry publication is not atomic. A failed run can leave packages visible before
the complete GitHub Release exists; use the original run and publication records to recover.
The old `ci.yml` version-tag trigger is removed so these jobs own product release publication.

## Contribution collections

The shared `.changes/v1.0/` collection is included in each 1.0 beta, RC, stable
and patch release from its selected source commit. Exact-version collections add
release-specific notes. Other major/minor series remain separate. See the
[contributor guide](../../.changes/README.md) and
[annotated contribution template](../../.changes/RELEASE-NOTE-SAMPLE.yaml).
Run `python tools/release/notes.py --check --version v1.0.0-beta.1`
before preparation; CI and release source validation also validate contributions.
