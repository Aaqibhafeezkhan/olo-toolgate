# ADR-025: Unified tagged and development publication

**Status:** Accepted

## Context

The retained Gateway publisher produces versioned GHCR images and provenance.
Foundation publishes Maven, Helm OCI, raw contracts and immutable release assets.
These capabilities differ from main's Docker Hub development publication, but
their separate workflows repeat validation and obscure release ownership.

## Decision

The existing CI workflow accepts main pushes and matching `v<VERSION>` tags.
Each run executes the shared gates and all six native contexts once. One reusable
publisher consumes the exact run's successful artifact evidence. Main retains
development Docker Hub/Maven/prerelease publication. Version tags publish GHCR,
Helm OCI, source/image SBOMs, native clients, Java artifacts and provenance with
immutable GitHub assets. Remove obsolete component publishing jobs and the
standalone foundation release workflow.

Use the existing development-release environment for the common publisher.
The inspected Gateway and Foundation environments have no secrets, variables,
reviewers, wait timers or branch/tag restrictions; no protected settings are lost.
Their empty environment records can be removed after their callers are retired.

## Security Impact

RC admission, exact tag/version/commit validation, every test/scan, all native
platforms, checksums and successful same-run artifact verification remain required.
Only the publishing caller receives package, contents, OIDC and attestation write
permissions. No signing of native installers is claimed by provenance attestations.
No security threshold or isolation gate is relaxed.

## Operational and Compatibility Impact

Operators use one automatic CI/release workflow. A version tag runs its own
complete validation context, with no repeated checks inside that run. Explicit
manual build-only development publication keeps its previous behavior. Immutable
version tags and releases are never overwritten; failures may leave partial
registry publication and require operator review.

## Validation

Focused tests cover both channels, rejected mismatched/annotated tag targets,
caller permissions, required gates, immutable assets and publication command
plans. Pinned actionlint checks the workflow call graph. Remote publication is
verified only by the hosted publishing job after all gates succeed.

Local verification passed 96 focused regressions, actionlint 1.7.7, source quality
and read-only metadata/binding checks. An isolated Gradle 9.7.1 Maven-local proof
uploaded the exact three previously validated JARs, POM and Gradle module bytes
using only POM generation and publication tasks. No Java build/test task, remote
registry write, version tag or actual release was executed locally. Existing
source vulnerability and native runtime/installer failures remain blocking.
The common publisher rejects the configured Sonatype Portal upload URL before
artifact collection or credential transmission; a standard Maven repository
is required until a separately reviewed Central/GPG integration exists.
