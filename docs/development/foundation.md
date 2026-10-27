# Foundation developer workflow

## Before committing

Run `npm ci` to install workspace dependencies and the repository pre-commit hook.
For existing checkouts, `npm run hooks:install` installs it explicitly. The hook
runs Chrome Connect protocol tests, a pinned Gitleaks scan of the Git index,
TypeScript validation and the UI unit suite, including fast axe checks for
uncontained navigation controls and nested main landmarks. Docker must be running;
Java and a running backend are not required. Run the same checks manually with
`npm run precommit`. The hook also builds the production UI and runs Chromium's
download E2E test against inert HTTP fixtures for all six OS/architecture targets.
It checks stable saved filenames, response headers, cache policy, byte length,
SHA-256, immutable versioned copies, accessibility and mobile layout. This is the
same browser test used against the real Control container in CI; the local
fixture proof supplements the full container/integration gates. Install Chromium
once with `npm --workspace @olo-labs/toolgate-admin-ui exec -- playwright install chromium`.

For Windows commits, the hook additionally exports the staged source into the
official Playwright Linux container and runs the production browser proof there.
Docker is mandatory; the first run downloads the container image. This catches
OS/browser differences that a Windows-only pass cannot establish. Each platform
uses its own browser context to avoid Chromium blocking a burst of downloads;
both CPU architectures are still checked.
Missing browser binaries or a failed browser check block the commit; no gate is
silently skipped. Secret-scan regression checks allow only the exact reviewed
Chrome public key at its identity path, and reject that key at another path,
credentials and private keys in the identity file. The reusable CI preflight runs
these checks before expensive builds. Ignored local configuration is not exported
into the staged scan; the full CI source scan remains enabled.

Stage UI/check changes before committing; the hook rejects unstaged changes in
those paths so the tested files match the proposed commit. A failing check blocks
the commit. Existing custom hook paths are preserved: add
`node tools/ci/precommit.mjs` to their pre-commit hook and
`node tools/ci/commit-msg.mjs "$1"` to their commit-msg hook. Installs using
`--ignore-scripts` need the explicit hook installation command. CI does not install
local Git hooks and continues running the full browser/accessibility and integration
gates; these fast checks supplement that coverage.

Commit subjects starting exactly with `RC:` also run every locally replayable
CI job before committing. Python validation dependencies and act are required
for this additional gate, and all source changes must be staged. Other subjects
keep only the existing smaller checks. See the
[local CI tools README](../../tools/ci/README.md#commit-checks) for setup and usage.

The workspace includes working Gateway, Control, Quickstart, console, client and
Java Tool SDK implementations. The Marketplace lives in a separate private
repository and is hosted at [olo-labs.org](https://olo-labs.org).
`make dev` remains a scaffold. Use the [development guide](README.md) for current
startup commands. `make check` is implemented and never silently skips a gate.

Install Java 21, Python 3.12+, Node 22, PHP 8.2+, Rust (the repository pins a stable
toolchain with fmt/clippy), Helm 3.17.3 and GNU Make. Gradle bootstraps its pinned,
SHA-256-verified distribution; a global Gradle installation is unnecessary.

```sh
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r tools/requirements.txt
make check PYTHON=python
make security PYTHON=python
```

Windows can use `gradlew.bat` and `python tools/check.py`. Set
`TOOLGATE_DOCKER_TOOLS=1` to run Rust, PHP and Helm in development containers when
native tools are unavailable. Java, Node, Python and Git still run locally. Module 02
requires Docker for real PostgreSQL tests; `make check` supplies isolated databases,
runs workspace/published Java builds and signed-token HTTP tests. The
scan hooks require Docker and network access to vulnerability databases.
Missing tooling, offline dependency failures and scan failures return nonzero.

The dependency license audit reads Cargo metadata, npm lock licenses, recursive
Python tool metadata and Maven dependency/parent POMs from Maven Central. It
fails for unknown or unapproved license metadata and writes
`build/release/dependency-licenses.json`. Apache/MIT/BSD/ISC are accepted for
runtime bindings (including the permissive Unicode-3.0 data license used by Rust
identifier tooling); MPL/EPL and Python licenses are accepted for build/test tools.

Gateway dependencies also use reviewed permissive [MIT-0](https://spdx.org/licenses/MIT-0.html)
and [Zlib](https://spdx.org/licenses/Zlib.html) licenses. Zlib source notices and
altered-source markings must be retained. The two legacy MIT/Apache Cargo slash
expressions are normalized to SPDX OR, as described by the
[Cargo manifest reference](https://doc.rust-lang.org/cargo/reference/manifest.html#the-license-and-license-file-fields);
the inventory retains the declared expression. Unknown expressions still fail.
BSD-only Python classifiers remain explicitly labeled as family evidence, not
an inferred SPDX revision. Generated Javadoc contains JDK assets under the
GPL classpath exception; it is a separate classifier artifact, not runtime code.
Source scans exclude ignored build/dependency caches. No advisory is suppressed.

The full gate validates versions, generated drift, headers and npm licenses,
offline schemas/compatibility/security fixtures, Java tests and publication,
Rust tests/fmt/clippy, TypeScript type tests/build/JSON transport, PHP DTO round
trips/lint and Helm lint/default/upgrade/invalid-value rendering. Foundation CI
also runs vulnerability, license and redacted secret scans. `make test` runs
schema/boundary tests; `make integration` runs the Maven publication proof.

Canonical contracts are `schemas/v1/*.schema.json`, with each named model in
`$defs`. Schema IDs are identifiers resolved from the local registry, not network
retrieval locations. All schema tests are offline. Shared fixtures live in
`tests/fixtures/contracts/v1`; the compatibility corpus and schema snapshots
are frozen acceptance baselines. Test signatures are explicit non-signatures.

```sh
python tools/contracts/generate.py
python tools/contracts/generate.py --check
./gradlew projects javaCheck build
./gradlew :contracts-java:publishToMavenLocal
python tools/check.py --publication-only
```

Generated bindings are structural serializers, not full schema validators.
Validate schema constraints before using input. Java/PHP/Rust reject unknown
object fields and unknown enum values in their decoders; TypeScript requires a
runtime schema validator in the consuming service. Error envelopes expose stable
codes, request IDs and retryability, with no exception text or arbitrary details.
Open JSON maps in tool input/output schemas are schema data, never credentials or
permission grants. Resource locators are descriptors, not validated filesystem
or network capabilities. ALLOW/ASK/BLOCK models do not authorize execution.

`VERSION` owns product/chart versions. `packages/contracts/VERSION` owns contract
versions. For an initially aligned release run
`python tools/contracts/version.py --set 0.1.0`, regenerate bindings, then update
locks with `npm install --package-lock-only --ignore-scripts`,
`cargo update`, `./gradlew javaCheck --write-locks` and
`TOOLGATE_UPDATE_LOCKS=1 python tools/check.py --publication-only`. Review all
lock changes and rerun checks. Separate workspace and artifact-mode Gradle locks
keep dependency-source proofs deterministic. Python validation tools are pinned
in `tools/requirements.txt`; scanners review their resolved transitives.

Local publication proof uses `.dev/maven-local-proof` through
`-Dmaven.repo.local`; it does not depend on a contributor's Maven cache.
`-PusePublishedContracts=true -PcontractsRepository=<repository>` selects an
exclusive Maven repository for the stable contract coordinate. An optional
`-PcontractsVersion=<semver>` selects a compatibility test version. Java services
keep identical imports in either mode. Java JARs also include canonical schemas.

Remote Maven repositories are configured only when `CI=true` and
`MAVEN_REPOSITORY_URL` is set. `MAVEN_USERNAME` and `MAVEN_PASSWORD` are protected
CI environment secrets, never Gradle properties or checked-in files. Publication
uses HTTPS and is configured through the common `development-release` GitHub
environment or repository settings. Maintainers configure reviewers and registry
permissions on that environment; no reviewers are currently configured.

`ci.yml` handles main and matching `v<VERSION>` tags. It runs each shared gate once,
packages Java/raw contracts/Helm and source SBOM/checksums in foundation, then
publishes the exact verified artifacts after all six native and image gates pass.
Tag publication includes Maven, Helm OCI, GHCR and GitHub provenance; main retains
its development channel. See [release process](release-process.md).
Manual publication requires the matching tag already to exist. Configure the
protected environment before using publication. Rust/npm/Composer package
publication remains registry opt-in; their identities and source packages exist.

Helm OCI is `oci://ghcr.io/olo-labs/charts/olo-toolgate:<chart-version>`.
Reserved runtime image names are `ghcr.io/olo-labs/olo-toolgate-{quickstart,
gateway,control}:<product-version>`.
No image is built or published until its owning module delivers a real service.

If drift fails, regenerate and inspect the schema diff before accepting it.
If a Maven proof fails, inspect the POM and the exclusive repository path rather
than falling back to the project. If Helm reports an unsupported value, the
opt-in service values and external secret/DB references must satisfy its schema. If a scan database cannot
be reached, repair network access and rerun; do not suppress the gate.

See [ADR 001](../adr/001-foundation-contract-generation.md),
[upgrade policy](../architecture/compatibility.md) and
[testing and evidence guide](../testing/README.md).

Module 02 uses reviewed Jakarta/Parsson EPL-2.0 dependencies without modifying them,
retaining upstream JAR notices and documenting matching source artifacts in
[dependency-license-reviews.json](../../tools/dependency-license-reviews.json).
Reviews match exact versions, expressions and runtime/build scope; upgrades require
a new review. The [classfile backport](https://github.com/smallrye/jdk-classfile-backport)
is GPL-2.0 with Classpath exception and is used only during Quarkus augmentation,
absent from runtimeClasspath/image. Its exception does not approve arbitrary GPL
dependencies. [EDL 1.0](https://www.eclipse.org/org/documents/edl-v10/) is identified
by Eclipse as BSD-3-Clause; UPL-1.0 is a reviewed permissive Graal build dependency.
Vert.x/JNA alternative licenses retain OR expressions, selecting Apache-2.0.
Public-domain declarations require an explicit CC0 source URL. Unknown metadata
and unreviewed runtime copyleft expressions still fail the gate.

Current Gateway checks exercise online Control authority. The benchmark measures ingress validation, not offline policy evaluation. See [performance methodology](../gateway/performance.md).

The locked same-file 1.0.6/walkdir 2.5.0 published README and LICENSE-MIT
explicitly offer MIT or Unlicense. The audit normalizes their historical
`Unlicense/MIT` declaration to `Unlicense OR MIT`, retaining the original
metadata and rejecting unknown slash expressions. See the
[Cargo license expression format](https://doc.rust-lang.org/cargo/reference/manifest.html#the-license-and-license-file-fields).

The exact locked webpki-root-certs 1.0.9 public certificate dataset is reviewed
under [CDLA-Permissive-2.0](https://cdla.dev/permissive-2-0/). Its complete license
text accompanies redistributed data in the Gateway image notices. This is an
exact-package review, not blanket acceptance of unreviewed data licenses.

Gitleaks flags public compact policy signatures as JWT-shaped text. The reviewed
allowance applies only to the `jwt` rule, exact public fixture paths and `jws`
field lines. Other fields and private-key rules remain scanned. The scan hook
executes a positive/negative proof that a credential field and an ephemeral
private key in those same paths still fail. No credential or private material is
excluded by the fixture allowance.

Source vulnerability/license scanning uses an immutable Linux snapshot of every
tracked working file and untracked non-ignored source. This avoids slow Windows
bind-mount traversal and concurrent drift-test file locks while retaining all
release sources and dependency lockfiles. Ignored caches/build outputs are
covered by resolved dependency and production-image inventories.

## Early release metadata gate

Run `make preflight PYTHON=python` (or `python tools/ci/preflight.py`) before
expensive builds. This dependency-free, read-only gate checks byte-exact release
notes, version metadata and generated bindings. CI runs it before native client
builds, containers and browser downloads. Regression tests reproduce stale notes,
missing notes, newline drift and changed version identity in temporary checkouts.

After changing the changelog, versions or schemas, regenerate and commit all outputs:

```sh
python tools/contracts/version.py
python tools/contracts/generate.py
python tools/ci/preflight.py
```

CI intentionally rejects drift rather than repairing it during a release.
