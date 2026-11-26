# How to release ToolGate

ToolGate is in Beta Release. Review the [current security and production limits](../../README.md#production-and-security-status) and their enhancement tickets before deployment.

## Branch and version model

Use major release lines and optional child lines for individual minor versions:

| Branch | Version config | Purpose |
|---|---|---|
| `main` | `version: 1.0.0`, `tag: dev` | Compatible development builds such as `1.0.0-dev.1` |
| `release/1.x` | `version: 1.0.0`, `tag: dev` | Qualified releases such as `1.0.0-beta.1` |
| `release/1.1.x` | `version: 1.1.0`, `tag: dev` | Minor line; subsequent fixes increment only its patch |
| `release/2.x` | `version: 2.0.0`, `tag: dev` | A future breaking major version |

`version.json` is the only manually maintained version configuration. Its tag
stays `dev` on every branch, so CI and development builds on `main` and `release/*`
are `<version>-dev.<n>`; builds from any other branch are
`<version>-dev-branch-<branch>.<n>` (see [development releases](development-releases.md)).
The release channel is not stored in the file: each **Build and release binaries**
run selects `dev`, `alpha`, `beta`, `rc` or `release` (default `dev`). The
`release` channel publishes the plain version, such as `1.0.0`, with no channel
or build suffix; the other channels publish `<version>-<channel>.<build>`.
Release preparation adds a commit changing `version.json` and preparing the
target contributor collection on the new branch. Package manifests, `VERSION`, SDK bindings, Helm metadata and platform versions are
materialized in build checkouts; do not manually change individual versions.
Generated files retain their checked-in baseline until build preparation.

The release branch moves as reviewed compatible changes arrive. Each release
build selects an exact source commit and allocates an immutable identity.
A new workflow dispatch increments the release counter even when source code is
unchanged. Retrying the same Actions run retains its original number. Failed
reservations are not recycled; gaps are expected. Numbers are allocated per
base version and shared across release channels. Published stable versions omit
the counter and cannot be published again with different bytes.

Examples:

- `1.0.0-alpha.1`, `1.0.0-beta.2`, `1.0.0-beta.3`, then `1.0.0-rc.4`, then
  `1.0.0` (channel `release`; build 5 is recorded but not part of the version).
- A bug fix after stable: select `increment-patch` on the release branch to
  prepare `1.0.1`, then qualify and publish it from that same branch.
- A compatible feature: select `new-branch` from `release/1.x` to create
  `release/1.1.x` at `1.1.0`, optionally going through beta and RC again.
- A breaking change: select `new-branch` from main to create the next major
  line, such as `release/2.x` at `2.0.0`. Never synchronize breaking version-2
  changes into `release/1.x`.

The workflow rejects a release branch whose major disagrees with its config,
and a child line whose minor disagrees.
Maintainers must still review API/behavior compatibility; a version number alone
cannot prove that a source change is backward compatible.

## One-time repository setup


- Enable Actions read/write permissions, allow GitHub Actions to create pull
  requests, and allow the repository to publish GitHub Packages.
- Create the **production-release** environment. Restrict it to workflow executions on
  `release/*` (including `release/1.x`); configure reviewers if your release policy requires them.
  Initialization runs on main; build and publication run on the release line.
- GitHub npm, Maven and GHCR use the built-in `GITHUB_TOKEN` by default. Existing package
  access settings must grant this repository access.
- For a custom Maven repository, set environment variable `MAVEN_REPOSITORY_URL` and
  secrets `MAVEN_USERNAME` and `MAVEN_PASSWORD`. Credentials must be explicit for custom
  repositories; the GitHub token is never used as a fallback there.
- For Maven Central, set `MAVEN_REPOSITORY_URL` to
  `https://central.sonatype.com/api/v1/publisher/upload`, configure the Portal username/password
  plus `MAVEN_GPG_PRIVATE_KEY` and `MAVEN_GPG_PASSPHRASE`. See [signing setup](../../tools/release/README.md).
- Protect `release/*` branches against force pushes/deletion and require reviewed PRs
  for updates. Protect the `prepared/*`, `release-builds/*`, `release-runs/*`,
  `published/*`, `central-deployments/*`, `release-preparations/*`, and `v*` tags
  against updates/deletion.
  Permit the release automation to create new refs. These refs store immutable preparation,
  counter, build-run and publication records; do not delete them to reset a counter or retry.
- Allow 90-day Actions artifact retention, or adjust the two workflows to the repository's
  supported limit. Keep sufficient storage for five container archives and six native platforms.

Set `DOCKERHUB_USERNAME` and `DOCKERHUB_TOKEN` in `production-release` (repository secrets also work).
Set `DOCKERHUB_NAMESPACE` to the organization namespace, or omit it to use the Docker Hub username.
The same tested image archives are published to GHCR and Docker Hub with immutable version tags.
Retries verify existing image identity and reject conflicting versions; no images are rebuilt.
Existing development workflows remain separate and retain their own credentials/behavior.

## Prepare a new branch or increment a patch

Merge the release tooling into main first. Before creating child lines from an
existing release branch, synchronize these release tooling changes into that
source line through a reviewed PR. Preparation retains the selected source code;
it does not copy controller tooling into the source tree. Open **Actions → Prepare release branch**,
run the controller on **main**, and choose `source_branch`, `source_commit`
(`latest` or a full 40-character ancestor SHA), and `action`:

| Action | Source | Result |
|---|---|---|
| `new-branch` | `main` | Next major line: `release/2.x`, version `2.0.0`, when major 1 exists |
| `new-branch` | `release/1.x` | `release/1.1.x`, version `1.1.0`; if that child exists, `release/1.2.x`, version `1.2.0` |
| `new-branch` | `release/1.1.x` | Next unused minor line within major 1 |
| `increment-patch` | `release/1.x` at `1.0.0` | Same branch, version `1.0.1` |
| `increment-patch` | `release/1.1.x` at `1.1.0` | Same child branch, version `1.1.1` |

New major selection uses the higher of the source major and one greater than
the highest major among existing canonical major/child lines. With no existing
lines, the source major is used. New minor selection uses one greater than the
higher of the source minor and the highest existing minor line within that major. Gaps are not filled. Only canonical major/child branch
names count; historical build-numbered or date-named branches do not.

```sh
# Create the next major line from main.
gh workflow run create-release-branch.yml --ref main \
  -f source_branch=main -f source_commit=latest -f action=new-branch

# Create the next minor child from a release line.
gh workflow run create-release-branch.yml --ref main \
  -f source_branch=release/1.x -f source_commit=latest -f action=new-branch

# Increment only the patch on the selected release branch.
gh workflow run create-release-branch.yml --ref main \
  -f source_branch=release/1.1.x -f source_commit=latest -f action=increment-patch
```

Preparation retains `tag: dev`. New branches reset lower version components to
zero and prepare their contributor collection in the same commit. From main,
move `.changes/main/` to the target major's series folder in the **new branch
only**; main's source tree remains unchanged. From a release branch, copy the
parent's series to the child's new series, retaining the parent records. Set
`.changes/config.json` to the target series and omit main staging from new
release branches. Fragment contents and modes are preserved; conflicting
collections or invalid/missing source fragments are rejected before ref writes.
Patch mode changes only `version.json` and retains the current collection.
 Patch mode requires a
canonical release branch and its current head; it rejects main and older commits.
It never creates a child line or updates main, parent, or sibling release lines.

Protected patch updates fall back to a PR with the exact prepared commit; merge
that PR before building the new version. Other permission/network failures stop
the run. Repository rules and Actions PR creation settings still apply; no
protection bypass is requested. New branch creation remains subject to rules.

Preparations are serialized and reserve their exact result in an annotated
`release-preparations/<run-id>` tag before changing branch refs. A retry of the
same run keeps its original version and commit. If a response is interrupted,
the retry checks whether that exact commit was applied before doing anything.
Source changes detected before ref updates and conflicting branch creation
fail rather than forcing updates. New branches remain pinned to the selected
commit even if the source moves after the final check. Patch updates require
a non-force fast-forward. Start a fresh dispatch after reviewing changed source.
Protect preparation tags against mutation/deletion; do not remove them to retry.

The run summary records the source, target, version, prepared commit and any PR.
Preparation does not reserve a build number, build binaries or publish packages.
Build dispatches and retries do not change the patch; release channels and build
counters remain separate. Existing immutable release records are preserved.

## Synchronize compatible changes from main

1. Land reviewed fixes or compatible features on main.
2. Choose the intended major or minor release line. Keep its own `version.json`
   and `tag: dev` when synchronizing source changes.
3. Open a synchronization PR into that release line with compatible source
   changes from main. Use release preparation for a new minor line or patch.
4. Run and pass the CI/security checks, review the source diff and merge.
5. Dispatch the release workflow from the release branch itself.

Do not force-push the release line. When main starts breaking work for the next
major, maintain the old line using compatible fix PRs/backports instead of
merging the breaking changes. Bring applicable fixes into main as well.

## Build and optionally publish

Open **Actions → Build and release binaries**, select the target line (for
example **release/1.x** or **release/1.1.x**), choose
`build-only` or `build-and-publish`, choose the `channel` (`dev`, `alpha`, `beta`,
`rc` or `release`; default `dev`), and leave `build_run_id` empty for a new build.

```sh
gh workflow run build-release.yml --ref release/1.x -f mode=build-only -f channel=beta
```

When reusing a `build_run_id`, the original build's channel and version are kept
and the `channel` input is ignored.

The workflow reads the base version at the dispatch commit, reserves the next number,
and records immutable `release-builds/<base>/<number>`, `prepared/<version>` and
`release-runs/<version>` bookkeeping tags. The generated `release.json` records
the selected source and version; it is a build artifact, not another tracked
version configuration. The build never follows a newer branch head halfway
through execution.

CI preflight, foundation tests,
dependency/license/secret scans, six native platform/installer gates and
production-container validation must succeed before bundle collection. Coverage
reports and the Sonar quality gate are not part of the release workflow. Build
stages do not receive registry publication credentials. The retained bundle
contains checksums, SBOMs, provenance, source/build metadata, native installers,
Java and language SDK/contracts packages, Helm assets and tested image archives.
Publication uses the `production-release` environment and any configured approval.

For a single build/test/publish run:

```sh
gh workflow run build-release.yml --ref release/1.x -f mode=build-and-publish
```

To publish an already successful build's exact bytes, including after the branch
has moved, supply its original Actions run ID:

```sh
gh workflow run build-release.yml --ref release/1.x \
  -f mode=build-and-publish -f build_run_id=123456789
```

Reuse must identify the same release line and trusted original workflow. Its
source commit, reservation, original successful gates and bundle checksums are
verified. A foreign line/run or incomplete bundle is rejected. Reuse does not
allocate a number or rebuild packages. Use **Re-run failed jobs** to continue the
same build; dispatch a fresh run with no reuse ID for a new release number.

## Published outputs


| Output | Destination |
|---|---|
| Contracts JAR, sources, Javadoc, POM and Gradle module | Configured Maven repository; default GitHub Maven Packages |
| `@olo-labs/toolgate-contracts` | GitHub npm Packages |
| Control, Gateway, Quickstart, device, secured device, LangGraph runtime (`olo-toolgate-langgraph-runtime`) | `ghcr.io/olo-labs/<image>:1.0.0-beta.42` and `docker.io/<namespace>/<image>:1.0.0-beta.42` |
| Helm chart | `oci://ghcr.io/olo-labs/charts/olo-toolgate:1.0.0-beta.42` |
| Installers, distributions, SDK JARs, all bot `.olo` bundles, standalone `.olotool` packages, bot/tool inventories, SBOMs and manifests | GitHub Release `v1.0.0-beta.42` |

The owner follows the repository owner if this repository is forked. Public npm, crates.io,
Packagist and Java SDK registry publication are not configured by this pipeline; their checked
distributions are available as release assets. Installers remain unsigned/not notarized,
as in the existing native packaging workflow. Provenance does not claim OS code signing.

All required registries and attestations must succeed before the GitHub release becomes
public. Dev/alpha/beta/RC releases are prereleases. npm publication initially uses an immutable build
tag; the `dev`, `alpha`, `beta`, `rc` or stable `latest` npm alias advances only after finalization and never
moves backwards. Container `dev`/`latest` aliases are not changed. GitHub stable releases
also leave the repository's `latest` selection unchanged.

`build-manifest.json` records the pinned release source commit, original build run and trusted
release workflow commit. Attestations identify the controller workflow; this manifest binds
its artifacts to the pinned source tree from that release line. `registry-publication.json` records
registry coordinates and container digests. Check these with the included checksums.

## Artifact versions and documentation

Local build preparation is `python tools/release/config.py --build 1`.
Development workflow run number 1 yields `1.0.0-dev.1`; release counter 1 in the
beta channel yields `1.0.0-beta.1`. Python uses equivalent PEP 440 syntax and
platform stores use numeric versions derived from the same identity. Development
validation builds may use their workflow run number; only release builds reserve
an authoritative release counter. GitHub release tags are `v<version>`.

Use [version.json](../../version.json) for current branch version/channel links.
Generated metadata contains build-derived literals. Historical changelogs,
screenshots and qualification records retain their recorded versions. Dependency
pins and independently versioned example tools are separate from product versions.

Shared contributor collections, such as `.changes/v1.0/`, are included in each
corresponding 1.0 release. Exact-version collections add release-specific notes.
The initial `.changes/v2.0/` collection carries all contributor fragments from
the unreleased 1.0 series into 2.0. Keep those credits and the original 1.0
records. Start a collection with valid fragments when preparing another new
series through branch preparation; missing or empty selected collections fail
before expensive builds. See the [contribution guide](../../.changes/README.md) and
[annotated template](../../.changes/RELEASE-NOTE-SAMPLE.yaml). Contribution assets
are generated during release; do not regenerate tracked release-note snapshots
in ordinary PRs.

For manual development artifacts, use [development releases](development-releases.md).
For current boundaries, see [production and security status](../../README.md#production-and-security-status).
