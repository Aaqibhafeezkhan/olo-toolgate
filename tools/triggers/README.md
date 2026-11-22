<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Trigger package mock-test stage

`package_tests.py` discovers package-owned `trigger-tests.json` manifests from
the source checkout. Each package must have its own explanatory `README.md`.
New Java trigger sources without a registered test manifest fail discovery;
duplicate package IDs, unsupported manifest formats, live provider modes,
cross-package build selection and paths outside the checkout are refused.

The foundation pipeline prepares the Java SDK and package CLI once, then runs
every registered package as an independent matrix job with `fail-fast: false`.
The foundation gate depends on the entire matrix before downstream images.
Each job runs the package tests, descriptor examples, archive pack/validation
and isolated trigger start/stop smoke, and retains its own reports/artifact.
Existing SDK/example integration gates remain required as well.

Maven dependency resolution can receive HTTP 429 from Maven Central. Each matrix
entry retries that specific download failure at most twice, after 5 and 15
seconds, keeping `gradle-1.log` through `gradle-3.log` in its uploaded evidence
folder. Compilation errors, failing tests, other HTTP errors and exhausted
retries fail the job. This does not retry trigger events, workflow effects or
provider requests, and does not serialize the independent package jobs.

Current registrations are the hotfolder and log packages plus the independent
signal adoption fixture. They use deterministic in-memory file/broker mocks;
this is not evidence that the remaining provider connectors already exist.
No provider account or credential is required by this test stage.

## Register a package

Put this current-format file beside the package's `build.gradle.kts` and README:

```json
{
  "schemaVersion": 1,
  "providerMode": "mock",
  "packageId": "acme.source-watch",
  "buildRoot": "examples/java-tools",
  "project": "source-watch",
  "startConfig": {"file": "signals/example.txt"}
}
```

`buildRoot` is repository-relative; `project` selects only this package within
that Gradle build. For a standalone fixture use its directory as `buildRoot`
and an empty `project`. `startConfig` is bounded, non-secret configuration for
the standalone smoke. Unit/descriptor examples supply the deterministic source
data and broker doubles. Python trigger support needs its own qualified runner
when M9 is implemented; it is not silently treated as Java or skipped.

Run discovery with `python tools/triggers/package_tests.py --matrix`. To run
one entry, supply `--package <repository-relative-directory>`, `--sdk-repo`,
`--cli` and, if needed, `--sdk-version-file`. CI supplies the SDK and CLI from
the same source revision. Provider mode is always `mock` in this stage.

## Provider mock requirements

As each remaining connector is implemented, its tests must mock the actual
provider protocol behind the shared runtime/broker interface. Keep fixtures and
fault injection with that package and document them in its README. Cover the
valid event plus the relevant altered-signature/body, freshness/replay,
duplicate/conflicting identity, tenant/account binding, credential rotation,
disconnect, lease loss, retry/backpressure, ACK/checkpoint order and DLQ cases.
Use a deterministic clock and synthetic secrets; no external endpoint, mailbox,
chat workspace, database or message bus is needed for package unit acceptance.
Do not replace an unimplemented runtime capability with ambient network access.

Mock provider acceptance and shared-runtime/stack integration are separate
checks. The real log demo additionally verifies source → configured agent/model
→ independently retrieved Control result. Live external provider qualification
is not claimed by mocked protocol tests.

### Shared SDK preparation

CI uses a dedicated `GRADLE_USER_HOME` for the shared SDK stage. After SDK
publication, `--prepare-dependencies --sdk-repo build/sdk-repo` serially resolves
every registered package's resolvable classpaths without running tools or tests.
The producer bundles Gradle's dependency and bootstrap caches with the same-run
SDK artifact; it excludes cache locks and ZIP
downloads. It does not bundle user Gradle settings or credentials.
Package preparation and execution point Maven's local repository at the supplied
SDK repository, so neither depends on ambient `~/.m2` artifacts.

Each matrix job restores this bundle into its own fresh Gradle home and runs
the complete package checks with `--offline`. An incomplete cache fails without
network probing or an online fallback. Only the producer contacts Maven for
these dependencies, reducing repeated downloads across ephemeral matrix jobs.
Producer repository outages can still fail preparation; no cache or successful
download establishes passing package/runtime qualification.

The producer restores dependency files, metadata and the Gradle bootstrap cache
from previous successful runs before SDK publication. Its key tracks build
scripts, dependency locks, the version catalog and wrapper pin; older matching
OS caches can seed unchanged artifacts. A new cache is saved only after SDK,
CLI and package dependency preparation succeed. User Gradle configuration and
credentials are excluded. A cold/missing cache still requires Maven availability;
unavailable publication verification remains a failure and records its HTTP
status rather than assuming the dependency exists.
If this producer cache has never been populated, CI can seed its dependency
files and metadata from the branch-visible Linux JDK 21 Java cache. The seed's
Gradle settings, init scripts and credentials are never copied into the producer
home. A seed is an optimization; missing artifacts still require the repository.
The seed restore uses the existing cache's exact `~/.gradle` path and Linux
JDK 21 key prefix so GitHub's path-based cache version matches. Only
`caches/modules-2/files-2.1` and `metadata-*` directories are copied into the
isolated producer home before publication.

Run `python tools/triggers/package_tests.py --prepare-sdk` from the repository
root to publish the shared test SDK. CI uses this command before the parallel
package matrix. It invokes `:publishJavaGradleSdkLocally`: all required SDK
libraries, contracts and the Gradle plugin are published, while the unused Maven
plugin is left to the full SDK publication/end-to-end gates. SDK preparation and each package use the shared
[Maven Central resolution recovery](../../docs/development/gradle.md#maven-central-resolution-recovery).
SDK attempts are retained under `build/trigger-package-sdk/logs/gradle-<attempt>.log`;
package attempts are under `build/trigger-package-tests/<package-id>/`.
Compilation and test failures stop immediately; exhausted recovery remains a failed build. The command does
not build the offline Rust package CLI; CI builds that separately afterward.
