# How to release ToolGate

ToolGate is in Beta Release. Review the [current security and production limits](../../README.md#production-and-security-status) and their enhancement tickets before deployment.

## Branch and version model

Use a long-lived branch for each supported major version:

| Branch | Version config | Purpose |
|---|---|---|
| `main` | `version: 1.0.0`, `tag: dev` | Compatible development builds such as `1.0.0-dev.1` |
| `release/1.x` | `version: 1.0.0`, `tag: beta` | Qualified releases such as `1.0.0-beta.1` |
| `release/2.x` | `version: 2.0.0`, `tag: beta` | A future breaking major version |

`version.json` is the only manually maintained version/channel configuration.
Supported channel tags are `dev`, `beta`, `rc` and `stable`. The source trees of
synchronized main and release branches differ only in this file. Package
manifests, `VERSION`, SDK bindings, Helm metadata and platform versions are
materialized in build checkouts; do not manually change individual versions.
The shared checked-in generated baseline is the same on both branches.

The release branch moves as reviewed compatible changes arrive. Each release
build selects an exact source commit and allocates an immutable identity.
A new workflow dispatch increments the release counter even when source code is
unchanged. Retrying the same Actions run retains its original number. Failed
reservations are not recycled; gaps are expected. Numbers are allocated per
base version and shared across release channels. Published stable versions omit
the counter and cannot be published again with different bytes.

Examples:

- `1.0.0-beta.1`, `1.0.0-beta.2`, then `1.0.0-rc.3`, then `1.0.0`.
- A bug fix after stable: update the shared base version to `1.0.1`, retain the
  release branch, qualify and publish `1.0.1`.
- A compatible feature: update the base version to `1.1.0` and use the same
  `release/1.x` branch, optionally going through beta and RC again.
- A breaking change: advance main to major 2 and initialize `release/2.x`.
  Never synchronize breaking version-2 changes into `release/1.x`.

The workflow rejects a release branch whose major disagrees with its config.
Maintainers must still review API/behavior compatibility; a version number alone
cannot prove that a source change is backward compatible.

## One-time repository setup


- Enable Actions read/write permissions and allow the repository to publish GitHub Packages.
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
  `published/*`, `central-deployments/*`, and `v*` tags against updates/deletion.
  Permit the release automation to create new refs. These refs store immutable preparation,
  counter, build-run and publication records; do not delete them to reset a counter or retry.
- Allow 90-day Actions artifact retention, or adjust the two workflows to the repository's
  supported limit. Keep sufficient storage for five container archives and six native platforms.

No Docker Hub credential is needed for this release path. Versioned images go to GHCR.
Existing development workflows remain separate and retain their own credentials/behavior.

## Initialize a release line once

Merge the release tooling into main first. Open **Actions → Prepare release branch**,
run on **main**, and choose the source branch/commit and channel. The base version
comes from the selected source's `version.json`.

```sh
gh workflow run create-release-branch.yml --ref main \
  -f source_branch=main -f source_commit=latest -f channel=Beta
```

A full 40-character ancestor commit SHA may replace `latest`. Initialization
creates `release/<major>.x` with the selected source tree and a channel-only
config change. It does not allocate a release build number. If the line already
exists, initialization refuses to overwrite it; use a synchronization PR.

Existing build-numbered branches and their immutable preparation tags remain
historical records. Do not move tags or reuse an old reserved version for new
source bytes. The active version-1 line is `release/1.x`.

## Synchronize compatible changes from main

1. Land reviewed fixes or compatible features on main.
2. Set the shared base version for the intended patch or minor release in
   `version.json`; main retains `tag: dev`.
3. Open a synchronization PR into `release/1.x`. Keep the release channel
   (`beta`, `rc`, or `stable`) in that branch's config while taking the intended
   compatible base version and source changes from main.
4. Run and pass the CI/security checks, review the source diff and merge.
5. Dispatch the release workflow from the release branch itself.

Do not force-push the release line. When main starts breaking work for the next
major, maintain the old line using compatible fix PRs/backports instead of
merging the breaking changes. Bring applicable fixes into main as well.

## Build and optionally publish

Open **Actions → Build and release binaries**, select **release/1.x**, choose
`build-only` or `build-and-publish`, and leave `build_run_id` empty for a new build.

```sh
gh workflow run build-release.yml --ref release/1.x -f mode=build-only
```

The workflow reads the config at the dispatch commit, reserves the next number,
and records immutable `release-builds/<base>/<number>`, `prepared/<version>` and
`release-runs/<version>` bookkeeping tags. The generated `release.json` records
the selected source and version; it is a build artifact, not another tracked
version configuration. The build never follows a newer branch head halfway
through execution.

CI preflight, coverage checks and the configured Sonar gate, foundation tests,
dependency/license/secret scans, six native platform/installer gates and
production-container validation must succeed before bundle collection. Build
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
Start a `.changes/v1.1/` collection for a new minor series; preserve the old
records. See the [contribution guide](../../.changes/README.md) and
[annotated template](../../.changes/RELEASE-NOTE-SAMPLE.yaml). Contribution assets
are generated during release; do not regenerate tracked release-note snapshots
in ordinary PRs.

For manual development artifacts, use [development releases](development-releases.md).
For current boundaries, see [production and security status](../../README.md#production-and-security-status).
