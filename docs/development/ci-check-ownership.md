# CI check ownership

`ci.yml` owns automatic validation. `ci-manual.yml` calls the same reusable stages
for explicit operator validation, bypassing admission. In automatic CI, admission and preflight run once;
foundation and the six native platform jobs then run in parallel. Images wait
for both. Main CI does not publish development releases. Manual development
publication selects any source branch; product publication retains its separate
full-test graph. See [development releases](development-releases.md) and
[how to release](how-to-release.md).

| Check | Main owner |
| --- | --- |
| RC title, allowed main ref and CI-enabled admission | admission |
| Release/version metadata, generated contract bindings | preflight |
| CI regressions, extension protocol/package regressions, staged secret proof | preflight |
| UI generation, source headers, canonical contract fixtures | foundation |
| Java checks/builds, PostgreSQL/SQLite unit and enterprise suites | foundation |
| Maven JAR/POM/sources/Javadoc, artifact-mode services and missing-dependency rejection | foundation |
| Workspace Rust format, unit tests and clippy on Linux x64 | foundation |
| Java SDK Gradle/Maven sample projects and protocol/package effects | foundation |
| TypeScript contract type checks/build/tests, UI types/unit/browser checks and production bundle/SBOM | foundation |
| PHP fixtures/lint; shared Helm lint and every component install/upgrade/negative/schema variant | foundation |
| Source dependency, license and secret scans, actionlint | foundation |
| Client native test/clippy on the other five OS/architecture contexts | clients |
| All six native builds, OS service lifetime, packaging, installer compile/smoke | clients |
| Linux x64 managed interpreter provisioning and OCI isolation | clients |
| Canonical public client/installer manifests and extension packaging | clients public-bundle |
| Composed Quickstart boot, browser/effect flow, persistence, SQLite and PostgreSQL modes; Quickstart Python unit/security suite | images |
| Production Control replicas/races/custody/shutdown, real PostgreSQL quarantine restore, native client downloads | images |
| Production Gateway delegated effects, headers/container proof and benchmark | images |
| Linux device enrollment/tools/health/container proof and LangChain native smoke | images |
| Every production image vulnerability scan, SBOM, archive and checksum | images |
| Raw contract/Java/Helm release assets, source release SBOM and asset checksums | foundation |
| Prepared source/version verification and product registry publication | Build and release binaries |
| Versioned Helm OCI, all five GHCR image attestations, native/source/image asset provenance | Build and release binaries |
| Pinned branch/SHA development packages and isolated Docker/npm aliases | Manual development release |

Foundation builds the UI once, including Chromium download/experience checks.
Gradle and CI's explicit `--prebuilt-ui` Docker stage consume that artifact.
Normal local Docker builds retain the source build. Quickstart's tested helper
image is reused for production Control and Gateway validation.

The same test on another OS/architecture, with published contracts, another
database mode or another production binary is a distinct context and remains.
Prepared product releases use the shared validation bodies in their own complete graph.
Explicit manual build-only publication keeps its existing behavior.
Legacy component workflows remain callable but are absent from the main call
graph, so their old job bodies do not repeat main checks.

## Failure audit, 12 October 2026

The failing main commit reviewed was `8191e986b07f72d294f180a6e9beff14fdcb5fb2`.

| Observed failure | Diagnosis and disposition |
| --- | --- |
| [Foundation](https://github.com/olo-labs/olo-toolgate/actions/runs/38184381931), [Control](https://github.com/olo-labs/olo-toolgate/actions/runs/38184381960), [Quickstart](https://github.com/olo-labs/olo-toolgate/actions/runs/38184381953) and policy helper builds | Control Docker context omitted newly included Java SDK project directories. Copy the complete SDK tree; focused debug production build now succeeds. |
| [Endpoint client](https://github.com/olo-labs/olo-toolgate/actions/runs/38184381922), Windows x64/ARM | Parked-gateway test fixtures supplied Unix IDs/socket paths. Use the real Windows SID/pipe shape, keeping production validation strict. Both exact tests passed on local Windows. |
| [Gateway source scan](https://github.com/olo-labs/olo-toolgate/actions/runs/38184381962) | HIGH vulnerabilities remain in Plexus Utils 3.6.0 and the frozen 2026-10-10 Java SDK compatibility record's Jackson 3.1.4 graph. The security gate remains blocking. Updating active dependencies and creating a new compatibility record require their own dependency/conformance validation; no scanner exclusion or threshold relaxation is included here. |

An earlier Chromium approval-state race was fixed by merged PR #77; latest
preflight completed successfully. The separate development release entry was
successful because publication was skipped, so it was not evidence of a release.

Local verification used focused regression tests, Windows unit filters,
client-only compilation/lint, installer compilation and the four requested batch
flows. No full repository test replay, hosted six-platform validation, real model
call or destructive uninstall of this working machine was performed.

Main advanced to `904b0bd477143a4f1f2032b8db0d11edc76b90a7` during this work.
Its [foundation run](https://github.com/olo-labs/olo-toolgate/actions/runs/38186453336)
and [native run](https://github.com/olo-labs/olo-toolgate/actions/runs/38186453309)
completed admission but skipped every validation job; the
[development publisher](https://github.com/olo-labs/olo-toolgate/actions/runs/38186465375)
also skipped. Their successful workflow summaries do not prove the gates passed.
The PR is rebased onto this newer main; the failure evidence above remains tied
to the specific failing commit.

After the trigger permission fix, [run 38188708660](https://github.com/olo-labs/olo-toolgate/actions/runs/38188708660)
passed admission and preflight. Foundation then failed two workflow-layout checks
in the contract suite that still expected independent foundation/client triggers
and preflight jobs. Those checks now validate the unified caller and compatibility
callers, including a negative case that rejects bypassing preflight. They run once
in the preflight CI suite, before native builds; contract/schema checks remain in
foundation. Focused workflow/admission/local-inventory tests verify the new layout.
The same run also failed Linux x64 managed runtime validation at the Dotnet
version/sandbox self-test. That distinct native failure is outside this workflow
layout fix; its isolation gate remains required.

The latest reviewed [run 38189496877](https://github.com/olo-labs/olo-toolgate/actions/runs/38189496877),
commit `522ff8c820f8e748af9148db9fab08e3aed07705`, passed admission/preflight.
Foundation built/tested successfully before its source vulnerability scan rejected
six HIGH findings: active SDK Plexus Utils 3.6.0 and Jackson 3.1.4 in the frozen
2026-10-10 compatibility record. Linux x64 still failed the Dotnet version/sandbox
self-test. Both Windows jobs passed unit tests then failed the initial silent
installer with exit 7; the logs lack an installer diagnostic log, so its cause
is not established. These distinct gates remain required and block publication.

In [run 38250572617](https://github.com/olo-labs/olo-toolgate/actions/runs/38250572617),
Linux and Windows native jobs passed. Both macOS jobs failed a new cold-engine
fixture that invoked Linux's `/bin/printf`; it now uses the POSIX shell builtin.
Foundation passed its build/test gates, then reported the same six HIGH findings.
The active Maven plugin now constrains Plexus Utils to the fixed 3.6.1 release;
its tests and local SDK publication passed. Windows Quickstart smoke now selects
`gradlew.bat`, builds a native CLI and uses an owned temporary starter project.

Source scanning still includes every inventoried file and retains the full
HIGH/CRITICAL vulnerability/license report. Dependency findings in the exact,
unchanged 2026-10-10 compatibility manifests are historical evidence and are
reported separately, without blocking publication. Their approved SHA-256 hashes
are checked against the scanned snapshot. Changed manifests, new records and
active dependencies remain blocking; all license and secret findings retain
their existing gates. CI uploads `source-scan-findings` even when the scan fails.
The five historical Jackson 3.1.4 findings remain visible in `historical.json`;
the frozen conformance record and its hashes are unchanged.

Browser login and configuration refresh checks now wait for their HTTP responses
and use the Control client's 15-second deadline. A later picker failure was tied
by request ID to SQLite transaction acquisition: read-only sessions unnecessarily
reserved its sole writer. Quickstart reads now use read-only WAL snapshots;
mutations retain IMMEDIATE serialization and commit without acquiring a second
writer reservation. Regression tests cover overlapping readers/writers, snapshot
isolation and rejection of writes in a read transaction.

In [run 38260283094](https://github.com/olo-labs/olo-toolgate/actions/runs/38260283094/job/114856032566),
Quickstart passed its browser and PostgreSQL checks before the production Control
smoke rejected the release's standard preset grants. The smoke now checks the
release-selected preset records, empty standard groups and disabled runtime
actors. It retains one installation Super Admin, then scopes the maker/checker
identities before testing independent review. The production image passed the
two-replica PostgreSQL smoke, including concurrent idempotency, restart, custody
and graceful shutdown. All 199 CI helper tests passed; six guard the updated
preset and review fixture expectations.

In [run 38266893091](https://github.com/olo-labs/olo-toolgate/actions/runs/38266893091/job/114880157459),
production Control and reviewed PostgreSQL restore passed. The client-download
browser checks still expected immutable caching for `0.10.0-dev`, although
prerelease filenames are reused and correctly receive `no-store`. The browser
checks now distinguish prerelease and final versions, validate uncached manifests
and stable aliases, and keep checksum verification. The local browser fixture
matches Control's headers and exercises both versions on every run. Control's
cache tests cover development, release-candidate and final versioned downloads,
stable aliases and configured Windows installers. Native buffer equality checks
the complete installer bytes within the browser's existing timeout.
