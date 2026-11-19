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

Run `python tools/triggers/package_tests.py --prepare-sdk` from the repository
root to publish the shared test SDK. CI uses this command before the parallel
package matrix. Only dependency-resolution HTTP 429 failures qualify for retries:
three attempts total, with 5- and 15-second delays. Each attempt is retained under
`build/trigger-package-sdk/logs/gradle-<attempt>.log`. Compilation and test failures
stop immediately; exhausted throttling remains a failed build. The command does
not build the offline Rust package CLI; CI builds that separately afterward.
