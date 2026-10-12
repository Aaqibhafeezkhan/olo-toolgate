# CI check ownership

`ci.yml` has one main push/dispatch entry point. Admission and preflight run once;
foundation and the six native platform jobs then run in parallel. Images wait
for both, and release waits for all required evidence from the same run and SHA.

| Check | Main owner |
| --- | --- |
| RC title, main branch and CI-enabled admission | admission |
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
| Exact run/SHA prerequisite verification, Maven/image/prerelease publication | release |

Foundation builds the UI once, including Chromium download/experience checks.
Gradle and CI's explicit `--prebuilt-ui` Docker stage consume that artifact.
Normal local Docker builds retain the source build. Quickstart's tested helper
image is reused for production Control and Gateway validation.

The same test on another OS/architecture, with published contracts, another
database mode or another production binary is a distinct context and remains.
Tag release and explicit manual build-only publication keep their existing paths.
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
