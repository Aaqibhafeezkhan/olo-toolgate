# Release

Release automation, checksums, signing/provenance and GitHub release helpers.

Both Maven publishing jobs support GitHub Packages, standard HTTPS Maven repositories,
and Sonatype Central. For Central, set the `development-release` environment variable
`MAVEN_REPOSITORY_URL` to `https://central.sonatype.com/api/v1/publisher/upload`.
Set these environment secrets:

- `MAVEN_USERNAME` and `MAVEN_PASSWORD`: the two values from a Central Portal user token
  with publishing access to the verified `io.ololabs.toolgate` namespace.
- `MAVEN_GPG_PRIVATE_KEY`: the complete ASCII-armored private signing key
  (`gpg --armor --export-secret-keys <fingerprint>`).
- `MAVEN_GPG_PASSPHRASE`: the key's passphrase; leave empty for an unprotected key.

Publish the signing key's public part to a public keyserver before releasing, as
required by [Central's GPG instructions](https://central.sonatype.org/publish/requirements/gpg/).
The publisher imports the private key into a temporary isolated keyring, signs the
JARs, POM and optional Gradle module, adds checksums, and uploads a Maven-layout ZIP.
It waits for `PUBLISHED`, reports validation errors, and records the deployment ID
in the job summary. A timeout retains that ID: inspect its Portal status before
retrying, since Central versions are immutable.

Manual releases sign the downloaded build artifacts. Tagged releases retain their
checksum and coordinate checks before signing the exact tested artifacts. Main
development releases build Java artifacts with their unique publication version.
The contracts POM includes Central's required license, developer and SCM metadata.
GitHub Packages and other Maven repositories retain their existing Gradle path.

The dedicated signing key's public certificate is in `central-signing-key.asc`.
Fingerprint: `D9B1F04C5F8D5A2F71399FC440366EFD376915A0`; expires October 14, 2028.
Its private key and passphrase are configured as `development-release` environment
secrets. The public key is published to `hkps://keyserver.ubuntu.com`. Rotate the
environment secrets and this public certificate together before expiration.
