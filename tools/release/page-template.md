ToolGate is in **$status**. This release contains the packages built and tested for **$version**.

## Download and install

### Containers

Pull the Quickstart image for single-node evaluation:

```sh
docker pull $quickstart
```

$images

Use the version tag `$version` or a recorded immutable digest. See the [deployment guide]($repo/blob/$tag/docs/deployment/README.md) for configuration and startup instructions.

### Endpoint clients

Choose a versioned asset matching your operating system and CPU:

| Platform | Installer | CPU options |
|---|---|---|
| Windows | `.setup.exe` | `x86_64`, `aarch64` |
| macOS | `.dmg` | `x86_64`, `aarch64` |
| Linux | `.run` | `x86_64`, `aarch64` |

Linux and macOS `.tar.gz` distributions are also attached. Prefer filenames containing `$version`; unversioned filenames are compatibility aliases. Endpoint installers are unsigned; macOS installers are not notarized.

### Java contracts

```xml
<dependency>
  <groupId>io.github.olo-labs</groupId>
  <artifactId>toolgate-contracts</artifactId>
  <version>$version</version>
</dependency>
```

See `registry-publication.json` for the configured Maven repository. Contracts JARs are distinct from the Java authoring SDK. Use the attached asset list as the inventory of available SDKs, language packages, and deployment archives.

## Verification and build information

- Verify downloads against `SHA256SUMS` or the installer's individual `.sha256` file.
- Source: [`$commit`]($repo/commit/$commit).
- Build identity, test evidence, and version metadata: `build-manifest.json` and `release.json`.
- Registry destinations and immutable image digests: `registry-publication.json`.
- Component SBOMs, compatibility metadata, and contribution records are attached.

## Production and security status

Review the [production and security status at this release]($repo/blob/$tag/README.md#production-and-security-status) before deployment. It describes the release's limitations and links to open enhancement tickets. Quickstart is intended for single-node evaluation and provides no high-availability guarantee.

$contributions

## Historical changes and license

See [CHANGELOG.md]($repo/blob/$tag/CHANGELOG.md) for historical changes. This page is generated from release metadata, registry receipts, and the corresponding contribution records; it does not embed the historical changelog.

Licensed under Apache-2.0. See `LICENSE`, `NOTICE.md`, and the attached SBOMs.
