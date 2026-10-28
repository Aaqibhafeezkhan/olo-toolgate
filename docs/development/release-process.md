# Release process

Release branches are long-lived major lines such as `release/1.x`. Compatible
patch and minor releases share the line; breaking changes start a new major
line. Each new release build reserves an immutable version and source commit.
See [How to release ToolGate](how-to-release.md).

Use the [release operator guide](how-to-release.md) for repository setup, version
allocation, release branch preparation, build inspection, publication and retries.
It is the maintained source for registry credentials and release outputs.

- **ToolGate CI** validates push/manual runs admitted by the RC and CI enablement gates.
- **ToolGate CI - Manual** runs the same full validation stages on explicit dispatch,
  bypassing those admission gates. Use it to qualify the source before preparing a release.
- **Prepare release branch** initializes a long-lived major release line without allocating a build.
- **Build and release binaries** pins a commit and allocates a build on the release line, then tests and optionally publishes its
  verified artifacts under the protected `production-release` environment.
- **Manual development release** builds and publishes development packages without
  tests; see [development releases](development-releases.md). It is not release qualification.

All first-party artifacts share one version. Registry writes are not atomic:
inspect partial publication and follow the operator guide before retrying. Reuse
matching artifact bytes; never replace a conflicting published version.

Contributor changes accumulate in [versioned YAML fragments](../../.changes/README.md).
Development and prepared release bundles include the combined release notes,
contributor-only notes and machine-readable contributor manifest from the selected
source commit. The planned collection version is independent of allocated build
numbers. Update `.changes/config.json` when starting the next release collection.

See [CI ownership](ci-check-ownership.md), [admission](ci-admission.md),
[verification](../testing/README.md) and [compatibility](../architecture/compatibility.md)
for validation boundaries. Build provenance does not imply OS signing: development
installers are unsigned and macOS notarization is not configured.
