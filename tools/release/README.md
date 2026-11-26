# Release

Workflow profiles are built from `bot/configurations/` by the foundation
profile gate. Versioned `.olo` files and `olo-profiles-<version>.json` are retained
as `foundation-stage-profiles`, revalidated into foundation release assets, and
collected unchanged by development/tagged/product publication. Manual development
builds use the same builder and validator. `tools/release/profiles.py` refuses
source/version/hash mismatches, incomplete or duplicate configuration/workflow
inventories, and mismatched embedded workflow IDs, and checks every embedded tool archive. Release
promotion does not rebuild profile packages. See [profiles](../../bot/README.md).
All configurations are mandatory release assets, including both hotswap variants
even though they share a workflow ID. Users download their selected `.olo` from
GitHub Release Assets and explore with the published containers launched from
`deploy/docker/quickstart/dev`; local bot/tool builds are an authoring option.

## Standalone tool and trigger release assets

Development and production releases also publish `.olotool` archives separately:
all Java tool/mock example projects, all configured `core-mcp` packages, and
every exact archive embedded in a released bot. Immutable test/conformance
fixtures are not release packages. Foundation retains the example build outputs
as `foundation-stage-tool-packages`; the collector uses those and the checked
core lock without rebuilding them. Manual development builds compile the same
projects with core Java tests explicitly disabled; packaging is not qualification.

`tools/release/tool_packages.py` produces `olo-tools-<release-version>.json`.
Its `format` is `olo-tools`; `version` and `sourceCommit` identify the product
release. Each `packages` entry records the existing package metadata
(`packageId`, package-owned `version`, `packageDigest`, archive `sha256`, `bytes`
and embedded `path`), its standalone `filename`, and originating `projects`.
The package-owned version can differ from the product release version.
Filenames use safe package ID/version labels and the full archive SHA-256.
Identical bytes shared by projects/bots are published once; different archive
bytes retain separate assets even at the same package ID/version.

The collector refuses missing/ambiguous project output, an incomplete core lock,
tampered packages, missing embedded counterparts and unlisted standalone archives.
Promotion checks the inventory and preserves bytes. The release `SHA256SUMS`
covers the tool inventory and every `.olo`/`.olotool` alongside other assets.
These checks establish packaging/integrity, not runtime support, safety or grants.

Release branches include major lines such as `release/1.x` and minor child
lines such as `release/1.1.x`. Preparation selects a new major from main, a new
minor within the selected release major, or a patch increment on the same branch. Each new release build reserves an immutable version and source commit.
See [How to release ToolGate](../../docs/development/how-to-release.md).

For product releases, use the two manual jobs documented in
[How to release ToolGate](../../docs/development/how-to-release.md):
`create-release-branch.yml` prepares a new release line or increments a patch
(with a PR fallback for protected updates), and
`build-release.yml` allocates each immutable build, tests it and publishes or reuses its retained bundle. All first-party
release artifacts share one SemVer such as `1.0.0-beta.42`. Product publication uses
the `production-release` environment; development publication remains separate.

Release automation, checksums, signing/provenance and GitHub release helpers.

Maven publication is disconnected from both development workflows to avoid
registry publication limits. The reusable `release-maven.yml` workflow and all
publishing code are retained for compatibility callers. The new product release
pipeline publishes prepared contracts to Maven and resumes partial uploads safely.
Java artifacts remain part of the builds and GitHub releases.

The retained Maven publisher supports GitHub Packages, standard HTTPS Maven repositories,
and Sonatype Central. For product releases, configure `production-release`; legacy
development/compatibility callers use `development-release`. For Central, set the environment variable
`MAVEN_REPOSITORY_URL` to `https://central.sonatype.com/api/v1/publisher/upload`.
Set these environment secrets:

- `MAVEN_USERNAME` and `MAVEN_PASSWORD`: the two values from a Central Portal user token
  with publishing access to the verified `io.github.olo-labs` namespace.
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

The retained manual publishing mode signs downloaded build artifacts. Tagged mode
checks checksums and coordinates before signing the exact tested artifacts. The
main development publishing mode builds Java artifacts with a unique publication version.
The contracts POM includes Central's required license, developer and SCM metadata.
GitHub Packages and other Maven repositories use an isolated Gradle publisher
of the prepared bytes; no source build is replayed for manual or tagged artifacts.

The contracts coordinate is `io.github.olo-labs:toolgate-contracts`. Java package
names remain unchanged. Update Maven/Gradle dependency declarations from the old
`io.ololabs.toolgate` group ID.

Reusable workflows `client.yml`, `images.yml` and `release-images.yml`
are shared by both entry points. Automatic CI runs tests without publishing; manual disables test and smoke steps
and publishes its own build output from any selected branch/ancestor commit.
See [development release instructions](../../docs/development/development-releases.md).
The common Docker Hub publisher and GitHub draft-release uploader are used by both
paths. Draft uploads resume matching files after an interrupted response, and
the complete file set must match before the release becomes public. After draft
creation, discovery polls up to six times with bounded backoff to allow GitHub's
release listing to catch up. Missing drafts time out without uploading or
publishing; authentication errors and identity/asset mismatches still fail closed.

The dedicated signing key's public certificate is in `central-signing-key.asc`.
Fingerprint: `D9B1F04C5F8D5A2F71399FC440366EFD376915A0`; expires October 14, 2028.
Its private key and passphrase are configured as `development-release` environment
secrets. The public key is published to `hkps://keyserver.ubuntu.com`. Rotate the
environment secrets and this public certificate together before expiration.

## Generated release descriptions

`page-template.md` defines the public release layout; `page.py` fills it from
verified release identity, the registry publication receipt, and `.changes`
contribution records. Build-time `notes.py` generation uses the same renderer.
Finalization renders the public description again after registry publication,
including GHCR and Docker Hub digests. Versions, channels, source links and human
credits are derived from metadata; the historical changelog is linked separately.

Edit the template or contribution records through normal source changes. Do not
hand-edit a generated release description. Tracked release-note snapshots and
previously published artifact bytes are preserved.

Run `python -m unittest discover -s tests/ci -p test_release_page.py` and the
release-note/product-release regression suites when changing this renderer.
