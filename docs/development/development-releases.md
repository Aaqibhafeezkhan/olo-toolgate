# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
# Development releases from main

## One manual trigger, build and publish only

Open **Actions → Manual development release (build and publish only) → Run
workflow**, select **main**, and run it. No inputs or `RC:` comment are required.
The direct link is
[manual development release](https://github.com/olo-labs/olo-toolgate/actions/workflows/manual-development-release.yml).
The CLI equivalent is `gh workflow run manual-development-release.yml --ref main`.

This explicit manual workflow works while automatic CI is paused. It builds fresh
artifacts from the selected main commit without test suites, CI admission/preflight,
format/lint checks, security scans, smoke tests, signing or waiting for CI evidence.
Compilation and packaging still need to succeed. Existing packaging tools retain
their payload format and dependency-download integrity checks.

One run publishes:

- Control, Gateway, Quickstart and Linux device containers to the configured Docker Hub namespace,
  with unique `dev-<12-character-commit>-<run-id>-<attempt>` tags, the commit's
  `dev-<12-character-commit>` alias, and `dev` if that commit is still current main.
- Java main/source/Javadoc JARs and POM to Maven, and the TypeScript library to GitHub
  npm Packages with the `dev` distribution tag. Registry versions include the run ID,
  attempt and commit, so a rerun gets fresh coordinates.
- The Helm chart to GitHub OCI, and a GitHub prerelease containing raw contracts,
  Rust `.crate`, PHP distribution, TypeScript tarball, Java artifacts, six native
  clients/installers, checksums, licenses and release metadata. Windows ZIPs remain
  internal build inputs; public Windows installation uses the combined installer.

The packaged chart points to this run's Docker Hub namespace and unique development
image tag. Kubernetes deployment credentials and enablement remain installation settings.

| Artifact | Purpose | Location |
|----------|---------|----------|
| Control, Gateway, Quickstart, Linux device containers | Run platform services and endpoint devices | Docker Hub (`dev-<commit>` tags) |
| Native client installers (`.setup.exe`/`.dmg`/`.run`) | Desktop installation | [GitHub Releases](https://github.com/olo-labs/olo-toolgate/releases) prerelease assets |
| Java JARs + POM | JVM integration | Configured Maven repository (GitHub Packages by default) |
| TypeScript library | Node/browser integration | GitHub npm Packages (`dev` dist-tag) |
| Rust `.crate` / PHP distribution | Rust and PHP integration | [GitHub Releases](https://github.com/olo-labs/olo-toolgate/releases) prerelease assets |
| Helm chart | Kubernetes deployment | GitHub OCI registry |

The source product/wire version stays unchanged; Rust crate/native binary versions
retain their source version. Release metadata identifies the commit and states
`verification: not-run` and `tests: not-run`. Production `latest` is unchanged.

Use the existing `development-release` environment/repository secrets:
`DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN`, and optional `DOCKERHUB_NAMESPACE` variable.
Maven defaults to GitHub Packages using `GITHUB_TOKEN`; a custom Maven URL requires
`MAVEN_REPOSITORY_URL`, `MAVEN_USERNAME` and `MAVEN_PASSWORD`. GitHub npm/Helm use the
workflow's Packages write permission. Any configured GitHub environment approval
still applies. Registry publication is not atomic; inspect a failed run before
rerunning it. After native packaging and the shared build succeed, the core
image/Helm/GitHub release, TypeScript, Maven and Marketplace publishers run as
parallel jobs. They consume the prepared artifacts and have no dependencies on
each other. A failure still fails its job and the workflow, while the other
publishers continue. The core publisher retains the ordering needed to include
container digests in the GitHub prerelease. This workflow has no automatic
push/tag/schedule trigger.

## Existing CI-backed development releases

The release stage of **ToolGate CI and release** publishes after foundation,
all six native platform jobs, public client packaging and production image checks
succeed. It verifies and downloads artifacts from that exact workflow run and
commit. Failed, skipped, cancelled or missing prerequisites prevent publication.
Dispatch `ci.yml` on main to repeat this complete flow. The publisher does not
wait for its own unfinished job. Legacy five-run preparation remains available
only for compatibility callers. Shared preparation hands the verified artifacts
to independent core release, Maven and Marketplace publish jobs. A registry or
Maven configuration failure cannot skip or cancel a sibling publisher.

Automatic CI and development release admission require `TOOLGATE_CI_ENABLED=true`
and a PR title starting with `RC:`. Push and release events resolve the merged PR
for the exact commit through GitHub's API, so the default `Merge pull request ...`
commit message is accepted when the PR has an RC title. Manual dispatch checks
the selected commit's merged PR title and requires no comment input.

Configure the `development-release` GitHub environment. Repository secrets also
work: `DOCKERHUB_USERNAME` and `DOCKERHUB_TOKEN` are required. The optional repository
variable `DOCKERHUB_NAMESPACE` overrides the Docker Hub username as the destination.
The token must have push access to the destination repositories:

- `olo-toolgate-gateway`
- `olo-toolgate-control`
- `olo-toolgate-quickstart`
- `olo-toolgate-device`

Images come from checksummed CI image archives, without rebuilding. Each gets a
`dev-<12-character-commit>` tag. A successful build of the current main commit also
updates `dev`. No production `latest` tag is changed.

The Linux device image is built and tested in the existing Quickstart job using
the same native client archives as Quickstart. Its checksummed `device-evidence`
artifact is published by the same development release job. The manual release
also builds the device from its just-built Quickstart image and publishes it in
the existing container loop. See [Linux device deployment](../deployment/device.md)
for comma-separated gateway URLs, automatic connection retries and enrollment.

GitHub Releases receives an immutable prerelease named
`dev-<full-commit>-<publishing-run-id>`, including six native client archives
(Windows, macOS and Linux; x64 and ARM64), checksums, embedded client SBOMs, image
SBOMs, raw contracts, Java JAR/source/Javadoc/POM and development-release.json.
The native archives retain their tested VERSION; release metadata ties them to
the exact commit and CI run IDs. The image digests are recorded in the metadata.
No installer/runtime signing is claimed by this workflow.

The Maven library is `io.ololabs.toolgate:toolgate-contracts`. Each publishing run
uses `<core-version>-dev.<publishing-run-id>.g<12-character-commit>`, avoiding reuse
of the checked-in development coordinate. Source version changes occur only in the
CI checkout. Java contract tests run in foundation before remote publication;
publication does not run the same suite again.

Maven defaults to `https://maven.pkg.github.com/olo-labs/olo-toolgate`, authenticated
with the CI GITHUB_TOKEN. Consumers need GitHub Packages read access. To use another
repository, set `MAVEN_REPOSITORY_URL`, `MAVEN_USERNAME` and `MAVEN_PASSWORD`. For a
custom repository use both Maven secrets; the workflow does not implement Maven
Central Portal upload or GPG signing. Versioned publication uses the same `ci.yml`
workflow and environment; see [release process](release-process.md).

Publishing requires GitHub Actions write access for repository contents/packages.
Existing package access controls must allow this repository's GITHUB_TOKEN. Keep
credentials in GitHub Secrets, never in files. Review environment protection and
Docker Hub repository access before the first publication.

If a run fails after one registry succeeds, partial artifacts may exist. Do not
claim an atomic multi-registry release. Inspect the publishing run before retrying;
Maven coordinates and GitHub prerelease names are unique per workflow run. A new
manual run on main can recover after credentials or registry access are corrected.

Release notes and licenses
-------------------------

`python tools/contracts/version.py` generates `RELEASE-NOTES.md` from canonical
versions and CHANGELOG.md; `--check` detects drift. Regenerate bindings afterward
with `python tools/contracts/generate.py` to synchronize library documentation.
GitHub release creation also generates commit/contributor notes automatically.

Client archives, raw contract bundles and language packages include LICENSE,
NOTICE.md and RELEASE-NOTES.md. Every Java main/source/Javadoc JAR includes them
under META-INF. Container images expose notes at
`/usr/share/doc/olo-toolgate/RELEASE-NOTES.md` and license notices under
`/usr/share/licenses/olo-toolgate/`. Existing dependency notices and SBOMs are
preserved. Release notes and notices are included in asset checksums.

Native CI now also builds six unsigned interactive installers. The public bundle
contains `installers.json`, `.setup.exe`/`.dmg`/`.run` files and their checksums.
The anonymous Control home page links to these verified installers. See
[installation and debugging](../client/installers.md) for permissions, initial
installation limits and signing deferred by request.
