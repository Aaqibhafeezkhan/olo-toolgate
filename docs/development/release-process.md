# Release Process

Product releases use **Prepare release branch** (`create-release-branch.yml`) and
**Build and release binaries** (`build-release.yml`). Follow the
[step-by-step release guide](how-to-release.md) for inputs, version allocation,
build-only inspection, publication, credentials and recovery.

**ToolGate CI** (`ci.yml`) validates main without publishing development builds.
Admission and preflight run once, then foundation checks and six native platform
jobs run in parallel. Production image checks consume their verified artifacts.
Development publication is explicitly manual and accepts any source branch plus
latest or an ancestor commit: see [development releases](development-releases.md).
See [check ownership](ci-check-ownership.md) and [RC admission](ci-admission.md).

## Publication channels

Manual development release publishes versioned images to Docker Hub and an immutable GitHub prerelease.
The product release workflow builds an immutable prepared commit and publishes
its tested image archives to versioned GHCR coordinates, its checked Helm chart
to OCI, contracts to Maven/GitHub npm Packages, and native/source/library assets
to GitHub Releases. All first-party artifacts share one version such as
`1.0.0-beta.42`. Source and image SBOMs, compatibility metadata, licenses, upgrade
notes, checksums and provenance remain included. A retained bundle can be
published later without recompilation.

Maven publication is disconnected from both development workflow entry points,
to avoid registry publication limits. The new product release workflow enables
Maven publication for prepared releases only. The reusable `release-maven.yml`
workflow remains available to compatibility callers.
Java packages continue to be built, checked and included in GitHub release assets.

Development publication uses the existing `development-release` environment;
product publication uses `production-release`. Gateway and
Foundation previously had distinct publication capabilities; those capabilities
are merged here. Their obsolete publishing jobs and the standalone foundation
release workflow are retired. The retired `gateway-release` and
`foundation-release` environments have no remaining workflow callers.
Configure reviewers and branch/tag restrictions on the common environment as needed.

Prepared-branch/version drift or a preparation tag resolving to a different commit prevents publication.
The retained Maven publisher uploads JARs, source/Javadoc classifiers, POM and Gradle module metadata
from checked foundation artifacts, with no Java rebuild or test replay. Product
publication verifies existing remote package bytes, resumes matching GitHub
draft assets, and fails closed on authentication or network errors. Provenance
for all five image digests and release assets must succeed before the GitHub
release becomes public. Registry writes across Maven, npm, GHCR and OCI are not atomic;
inspect partial publication before retrying a failed run. Shared preparation
collects verified artifacts without invoking Gradle before handing them to
the core release publisher. Manual development releases build Java artifacts
at the same development version as the binaries.
Maven version metadata remains available, but development runs do not upload to
a Maven repository. Failures remain visible in the job and overall workflow result.
The product publisher requires every registry and provenance step before
finalizing its GitHub release. Matching remote bytes are reused on retry;
conflicting versions are never replaced.

## Credentials and Maven Central

`DOCKERHUB_USERNAME` and `DOCKERHUB_TOKEN` are required for manual development
images. An optional `DOCKERHUB_NAMESPACE` variable selects an organization.
GitHub Packages and GHCR use the workflow's built-in `GITHUB_TOKEN`; existing
package access controls must grant the repository permission to publish.

The retained Maven publisher defaults to `https://maven.pkg.github.com/olo-labs/olo-toolgate`. A custom
HTTPS Maven repository uses the `MAVEN_REPOSITORY_URL` variable and
`MAVEN_USERNAME`/`MAVEN_PASSWORD` secrets. Existing repository settings work too;
they do not need to be duplicated in the environment.

The contracts Maven coordinate is `io.github.olo-labs:toolgate-contracts` under
the verified GitHub namespace. Consumers must update their dependency group ID
from `io.ololabs.toolgate`; Java package names remain `io.ololabs.toolgate.contracts`.
SDK module coordinates are independent of the contracts artifact.

For Central, use `https://central.sonatype.com/api/v1/publisher/upload` as the
repository URL. The shared Maven job signs a Maven-layout ZIP and uploads it
using a Portal user token with access to `io.github.olo-labs`. It waits for
Central to report `PUBLISHED`. GPG setup is documented in
[the release tools README](../../tools/release/README.md).
Product release finalization requires Maven, npm, images, chart and provenance to
succeed. Development publication remains independent of Maven.

Product releases and automatic CI both enable `run_tests` and complete foundation,
native and image verification. Only product releases publish afterward. Manual
development release permits workflow controls on any repository branch, accepts
only a source branch input and pins its latest commit once,
and sets `run_tests: false`: it builds and publishes without tests, scans or
smoke checks. Its image/GitHub and npm publisher jobs depend on source selection
and the completed build.

Development GitHub releases are assembled as drafts. Uploads run one file at a
time and resume existing assets only when their SHA-256 digest and size match
the prepared file. The release becomes public only after the complete asset
set is verified. An existing published release must already match all prepared
bytes; conflicting assets are never replaced.

## Retained validation contexts

Foundation retains canonical contracts, every binding, Java/SDK tests, both
database modes, policy/custody suites, UI/browser/accessibility, dependency/license/
secret scans, Maven artifact consumption and Helm schema/negative variants.
The image owner retains production Control/Gateway security and replicas,
quarantine restore, anonymous native downloads, Quickstart in SQLite/PostgreSQL,
Device/LangChain smoke, HIGH/CRITICAL image scans and CycloneDX SBOMs. Tests on
another OS/architecture, database or production binary are distinct contexts.

Native clients retain unit/security checks and real system-service/installer
smoke on all six target contexts. Development installers remain unsigned and
macOS notarization is not configured. Build provenance does not claim OS signing.
See [native operations](../client/hotfolder.md), [Control upgrades](../control-plane/upgrades.md)
and [compatibility](../architecture/compatibility.md).

Managed interpreter images remain separate approved OCI dependencies. Record
their exact digest, runtime version, license inventory, SBOM, vulnerability scan
and provenance before deployment. First-use pull requires an approved pinned image
and system-accessible engine; no engine installer or registry secret is embedded.

The explicitly dispatched [manual development release](development-releases.md#run-a-development-release)
retains its previously requested build-only exception. It has no automatic
push/tag/schedule trigger and does not claim CI validation or signing.
