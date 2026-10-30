# Development releases

Development packages are published only through **Manual development release
(build and publish only)**. Main CI runs validation and does not publish development
images, packages or GitHub releases. Product Beta/RC/stable releases use the
separate [two-job release guide](how-to-release.md).

## Run a development release

1. Open [Manual development release](https://github.com/olo-labs/olo-toolgate/actions/workflows/manual-development-release.yml)
   in Actions and select **Run workflow**.
2. Choose the repository branch containing the workflow controls under **Use workflow from**.
   Feature branches are supported; environment protection rules still apply.
3. Enter **source_branch**: `main`, a `release/*` branch, or any existing feature
   branch in this repository. Fork branches must first be pushed to this repository.
4. There is no commit input. The source branch's latest commit is resolved once
   at the beginning; all builders use that pinned SHA even if the branch moves.
5. Run the workflow and inspect its version/source summary. Download packages
   from the resulting GitHub prerelease.

CLI examples:

```sh
gh workflow run manual-development-release.yml --ref main -f source_branch=main

gh workflow run manual-development-release.yml --ref feature/my-change -f source_branch=feature/my-change

gh workflow run manual-development-release.yml --ref main -f source_branch=release/1.0.0-beta.42
```

The workflow builds and publishes without CI admission, lint, tests, smoke tests,
security scans or installer signing. Compilation, package integrity checks and
source/version checks still apply. Use it for development evaluation; product
releases retain their full validation and provenance gates.

## One version and isolated aliases

Every generated first-party binary/package uses one SemVer. Its base comes from the
selected commit's `version.json` and its build number is the workflow run number.
Builds from `main` or `release/*` are `<base>-dev.<run>`, for example `1.0.0-dev.42`.
Builds from any other branch are tagged `dev-branch-<branch>`, where the branch name
is lowercased with other characters replaced by `-` (up to 40 characters):
`feature/my-change` run 42 is `1.0.0-dev-branch-feature-my-change.42`. CI builds
follow the same rule; pull request builds use their source branch.
Native clients, CLI/browser host, containers, UI, contracts, Java, Rust, PHP,
TypeScript and Helm all receive that same version before compilation. Numeric-only
OS/extension fields are derived from the base version and workflow run number;
the run number must fit 1..65535. This does not allocate or consume product Beta/RC
release counters. Preparation does not commit version edits to the source branch.

Container tags use the full version. The Helm chart uses that version for its
chart, app version and image tags. GitHub prerelease names also include a branch
key and run identity. Branch keys use a readable slug plus a hash, so similar
branch names cannot share an alias.

Docker and npm expose a `dev-<branch-key>` alias only when the selected SHA is still
the current head of that branch. Docker also exposes `dev-branch` for the latest
published development build from a branch other than `main` and `release/*`, even
when that branch has advanced while the build was running. This
channel moves between feature branches as their builds publish; use the
versioned tag or `dev-<branch-key>` to select a particular build or branch.
For example, `ololab/olo-toolgate-quickstart:dev-branch` follows branch development
builds, while the release metadata records the source branch, commit and version.
The shared `dev` alias is updated only by a manual
release selecting **main** at its current head. A feature or release branch cannot
update shared `dev`, even if its SHA equals main. Older commits still publish their
unique version (and, outside `main` and `release/*`, the Docker `dev-branch` channel) without advancing branch-specific
aliases. Production `latest` is unchanged.

## Outputs and credentials

- Docker Hub: Control, Gateway, Quickstart, Linux device and secured Linux device.
- GitHub npm Packages: `@olo-labs/toolgate-contracts`.
- GitHub OCI: the Helm chart, pinned to this run's images.
- GitHub prerelease: six native clients/installers (Windows, macOS and Linux, x64
  and ARM64), browser extension, contracts, Rust/PHP/TypeScript packages, Java
  artifacts, release notes, licenses, checksums and development metadata.

Maven registry publication remains disconnected for development runs. Java JARs
and POM are included as release assets. Windows ZIPs remain internal installer
inputs. Development metadata explicitly records `verification: not-run` and
`tests: not-run`.

Use the existing `development-release` environment with `DOCKERHUB_USERNAME` and
`DOCKERHUB_TOKEN`. Optional repository variable `DOCKERHUB_NAMESPACE` overrides the
username. The token needs push access to all five image repositories. GitHub
Packages and OCI use the workflow token's Packages write permission; existing
package access and environment protection rules apply.

Selection runs separately from source preparation. Native/container builders
receive the pinned source and stamped files without publishing tokens. Publishing
jobs execute helpers from the controller commit on main, compare built metadata
to the original selection artifact pinned by its immutable artifact ID, and publish downloaded packages without
executing selected source code. Core and TypeScript publishers run independently
after the build; failure of one does not skip the other.

Registry publication is not atomic. **Re-run failed jobs** on the same workflow run
to finish a failed publication: a later attempt publishes the version the first
attempt selected and replaces what that version already published (image and Helm
tags are pushed again, differing or stale GitHub release assets are deleted and
uploaded again, and an existing npm version is deleted before it is published
again). Use a new manual run for fresh package coordinates. Runs for the
same source branch are serialized. GitHub concurrency may replace an older pending
run with a newer pending run; it is not a FIFO release queue.
