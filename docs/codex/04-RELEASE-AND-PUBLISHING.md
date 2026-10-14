# Release and Publishing

## Release Philosophy

A release is an immutable set of mutually compatible artifacts.

## Customer Runtime Artifacts

```text
ghcr.io/olo-labs/olo-toolgate-quickstart:<version>
ghcr.io/olo-labs/olo-toolgate-gateway:<version>
ghcr.io/olo-labs/olo-toolgate-control:<version>
```

Endpoint Client assets:

```text
Windows x64
Windows ARM64
Linux x64
Linux ARM64
macOS x64
macOS ARM64
```

## Marketplace Platform

```text
ghcr.io/olo-labs/olo-toolgate-marketplace-api:<version>
ghcr.io/olo-labs/olo-toolgate-marketplace-worker:<version>
```

Drupal container if maintained by project.

## Shared Contract Publication

Java:

```text
io.github.olo-labs:toolgate-contracts:<version>
```

Publish Maven-compatible artifacts.

The Gradle build must support:

```bash
./gradlew :contracts-java:publishToMavenLocal
```

and protected CI publication to configured Maven repository.

Also design publication for:

```text
Rust crate
npm package
Composer package
raw schema/OpenAPI/event bundle
```

## Helm

Package:

```text
olo-toolgate-<version>.tgz
```

and publish to an OCI registry such as:

```text
oci://ghcr.io/olo-labs/charts/olo-toolgate
```

## Release Assets

Each release should publish:

```text
checksums
SBOM
provenance
compatibility matrix
container digests
client binaries/installers
Helm package/reference
release notes
upgrade notes
```

## Version Sources

One release workflow determines versions.

Avoid hand-editing version strings across many files.

## Stable Release Gate

Release only when:

```text
unit green
integration green
E2E green
security green
compatibility green
Helm green
container scan acceptable
SBOM generated
upgrade test green
docs complete
```
