# Release Process

**ToolGate CI and release** (`ci.yml`) owns automatic main and version-tag
publication. Admission and preflight run once, then shared foundation checks and
six native platform jobs run in parallel. Production image checks consume their
verified artifacts; the common publisher requires every prerequisite from that
exact run and commit. See [check ownership](ci-check-ownership.md) and
[RC admission](ci-admission.md).

## Publication channels

Main publishes four tested images to Docker Hub and an immutable GitHub prerelease.
A matching `v<VERSION>` tag publishes
the same tested image archives to versioned GHCR coordinates, the checked Helm
chart to OCI, and native/source/image assets to GitHub
Releases. Source and image SBOMs, compatibility metadata, licenses, upgrade notes,
checksums and provenance remain included. Each tag has its own full validation
context; identical checks run once within that context.

Maven publication is disconnected from both development workflow entry points,
including the verified workflow's tag path, to avoid registry publication limits.
The reusable `release-maven.yml` workflow and publishing implementation remain
available for the future pipeline that will publish released packages only.
Java packages continue to be built, checked and included in GitHub release assets.

Both channels use the existing `development-release` environment. Gateway and
Foundation previously had distinct publication capabilities; those capabilities
are merged here. Their obsolete publishing jobs and the standalone foundation
release workflow are retired. The retired `gateway-release` and
`foundation-release` environments have no remaining workflow callers.
Configure reviewers and branch/tag restrictions on the common environment as needed.

Tag/version drift or a tag resolving to a different commit prevents publication.
The retained Maven publisher uploads JARs, source/Javadoc classifiers, POM and Gradle module metadata
from checked foundation artifacts through an isolated publication project, with
no Java rebuild or test replay. The exact release-tag lookup rejects an existing
GitHub release and fails closed on authentication or network errors. Provenance
for all four image digests and release assets must succeed before the GitHub
release is created. Registry writes across GHCR and OCI are not atomic;
inspect partial publication before retrying a failed run. Shared preparation
collects verified artifacts without invoking Gradle before handing them to
the core release publisher. GitHub development
releases include the checked foundation Java artifacts at their source version.
Maven version metadata remains available, but development runs do not upload to
a Maven repository. Failures remain visible in the job and overall workflow result.
The core publisher still requires image/chart publication and provenance before
creating the tagged GitHub release.

## Credentials and Maven Central

`DOCKERHUB_USERNAME` and `DOCKERHUB_TOKEN` are required for automatic development
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
Image, chart and GitHub release publication continue independently of Maven.

Both release paths call the same native, image and publishing workflows.
Automatic CI enables `run_tests` and completes foundation, native and image
verification before publication. The manual entry point is restricted to main
and sets `run_tests: false`: it builds and publishes without tests, scans or
smoke checks. Its image/GitHub and npm publisher jobs depend only on preparation.

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

The explicitly dispatched [manual development release](development-releases.md#one-manual-trigger-build-and-publish-only)
retains its previously requested build-only exception. It has no automatic
push/tag/schedule trigger and does not claim CI validation or signing.
