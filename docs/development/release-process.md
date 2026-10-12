# Release Process

**ToolGate CI and release** (`ci.yml`) owns automatic main and version-tag
publication. Admission and preflight run once, then shared foundation checks and
six native platform jobs run in parallel. Production image checks consume their
verified artifacts; the common publisher requires every prerequisite from that
exact run and commit. See [check ownership](ci-check-ownership.md) and
[RC admission](ci-admission.md).

## Publication channels

Main publishes four tested images to Docker Hub, versioned development Maven
contracts and an immutable GitHub prerelease. A matching `v<VERSION>` tag publishes
the same tested image archives to versioned GHCR coordinates, the checked Helm
chart to OCI, Java contracts to Maven, and native/source/image assets to GitHub
Releases. Source and image SBOMs, compatibility metadata, licenses, upgrade notes,
checksums and provenance remain included. Each tag has its own full validation
context; identical checks run once within that context.

Both channels use the existing `development-release` environment. Gateway and
Foundation previously had distinct publication capabilities; those capabilities
are merged here. Their obsolete publishing jobs and the standalone foundation
release workflow are retired. After those callers are merged, their empty
`gateway-release` and `foundation-release` environment records can be removed.
Configure reviewers and branch/tag restrictions on the common environment as needed.

Tag/version drift or a tag resolving to a different commit prevents publication.
Maven JARs, source/Javadoc classifiers, POM and Gradle module metadata are uploaded
from checked foundation artifacts through an isolated publication project, with
no Java rebuild or test replay. The exact release-tag lookup rejects an existing
GitHub release and fails closed on authentication or network errors. Provenance
for all four image digests and release assets must succeed before the GitHub
release is created. Registry writes across Maven, GHCR and OCI are not atomic;
inspect partial publication before retrying a failed run.

## Credentials and Maven Central

`DOCKERHUB_USERNAME` and `DOCKERHUB_TOKEN` are required for automatic development
images. An optional `DOCKERHUB_NAMESPACE` variable selects an organization.
GitHub Packages and GHCR use the workflow's built-in `GITHUB_TOKEN`; existing
package access controls must grant the repository permission to publish.

Maven defaults to `https://maven.pkg.github.com/olo-labs/olo-toolgate`. A custom
HTTPS Maven repository uses the `MAVEN_REPOSITORY_URL` variable and
`MAVEN_USERNAME`/`MAVEN_PASSWORD` secrets. Existing repository settings work too;
they do not need to be duplicated in the environment.

The current group ID is `io.ololabs.toolgate`. To retain it on Maven Central,
register `io.ololabs` and prove ownership of `ololabs.io` with Sonatype's DNS TXT
verification. If that domain is unavailable, choose a verified namespace before
changing public Maven coordinates. See [Sonatype namespace registration](https://central.sonatype.org/register/namespace/).

Central Portal is not a standard Maven PUT repository. Its release flow requires
Portal upload, GPG signatures and Central-compliant POM metadata; that integration
is not implemented by the current publisher. Do not use a Central Portal URL as
the custom repository URL expecting credentials alone to enable publication.
The common publisher rejects that target before artifact collection or credential
transmission, with an explicit configuration error.
See [Sonatype publishing requirements](https://central.sonatype.org/publish/publish-portal-maven/).

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
