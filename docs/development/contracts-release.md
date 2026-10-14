# Shared Contracts Release

## Release Unit

All contract artifacts are released as one contract-set version.

Example:

```text
1.6.0
```

publishes:

```text
olo-toolgate-contracts              Rust 1.6.0
io.github.olo-labs:toolgate-contracts  1.6.0
@olo-labs/toolgate-contracts        1.6.0
olo-labs/toolgate-contracts         1.6.0
raw contract bundle                 1.6.0
```

## CI Release Stages

```text
validate canonical schemas
        |
generate language bindings
        |
verify generated tree is clean
        |
compile every binding
        |
run compatibility checks
        |
build package artifacts
        |
SBOM/checksum/provenance
        |
publish using protected release environment
        |
create GitHub Release
```

## Registry Targets

The exact registries are configurable.

Recommended open-source defaults:

```text
Rust       crates.io or GitHub Packages where appropriate
Java       Maven Central and/or GitHub Packages
TypeScript npm registry and/or GitHub Packages
PHP        Packagist-compatible publication
Raw bundle GitHub Release
```

Choose final publication targets through an ADR before the first stable release.

## Split-Repo Migration

When a component leaves the monorepo:

1. pin a compatible contract-set version;
2. switch dependency resolution from local workspace to registry;
3. keep application imports unchanged;
4. run compatibility tests;
5. remove the component from monorepo workspace membership.

No schema copy/paste is allowed.
