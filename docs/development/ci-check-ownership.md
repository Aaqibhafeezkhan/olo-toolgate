# CI check ownership

`ci.yml` owns automatic validation. `ci-manual.yml` calls the same reusable stages
for explicit operator validation, bypassing admission. In automatic CI, admission and preflight run once;
foundation and the six native platform jobs then run in parallel. Images wait
for both. Foundation and images each run one job per gate group; their final
`foundation / check` and `images / verify` jobs are the release gates and pass only
when every group passed. Main CI does not publish development releases. Manual development
publication selects any source branch; product publication retains its separate
full-test graph. See [development releases](development-releases.md) and
[how to release](how-to-release.md).

| Check | Main owner |
| --- | --- |
| RC title, allowed main ref and CI-enabled admission | admission |
| Release/version metadata, generated contract bindings | preflight |
| CI regressions, extension protocol/package regressions, staged secret proof | preflight |
| UI generation, source headers, canonical contract fixtures | foundation / python |
| Java checks/builds, PostgreSQL/SQLite unit and enterprise suites | foundation / java |
| Maven JAR/POM/sources/Javadoc, artifact-mode services and missing-dependency rejection | foundation / java |
| Workspace Rust format, unit tests and clippy on Linux x64 | foundation / rust |
| Java SDK Gradle/Maven sample projects and protocol/package effects | foundation / examples |
| TypeScript contract type checks/build/tests, UI types/unit checks and production bundle/SBOM | foundation / ui |
| UI Chromium download/experience checks | foundation / browser |
| Configured core MCP packages, Python SDK against the built CLI, SDK worker tests | foundation / core-mcp |
| PHP fixtures/lint; shared Helm lint and every component install/upgrade/negative/schema variant | foundation / python |
| Source dependency, license and secret scans, actionlint | foundation / scans |
| Client native test/clippy on the other five OS/architecture contexts | clients |
| All six native builds, OS service lifetime, packaging, installer compile/smoke | clients |
| Linux x64 managed interpreter provisioning and OCI isolation | clients |
| Canonical public client/installer manifests and extension packaging | clients public-bundle |
| Composed Quickstart boot, browser/effect flow, persistence, SQLite and PostgreSQL modes; Quickstart Python unit/security suite | images / quickstart |
| Production Control replicas/races/custody/shutdown, real PostgreSQL quarantine restore, native client downloads | images / control |
| Production Gateway delegated effects, headers/container proof and benchmark | images / gateway |
| Linux device enrollment/tools/health/container proof | images / device, images / secured-device |
| LangChain native smoke on the device image | images / langchain |
| Standalone LangGraph worker/listener image, dependency smoke, vulnerability scan and SBOM | images / langgraph-runtime |
| Every production image vulnerability scan, SBOM, archive and checksum | images (the job that builds the image) |
| Raw contract/Java/Helm release assets, source release SBOM and asset checksums | foundation / check |
| Prepared source/version verification and product registry publication | Build and release binaries |
| Versioned Helm OCI, all five GHCR image attestations, native/source/image asset provenance | Build and release binaries |
| Pinned branch/SHA development packages and isolated Docker/npm aliases | Manual development release |

Foundation builds the UI once, in its `ui` job. The browser, Java and core MCP
jobs, Gradle and CI's explicit `--prebuilt-ui` Docker stage consume that artifact.
Normal local Docker builds retain the source build. Quickstart's tested helper
image is reused for production Control and Gateway validation; later image jobs
load it from the `quickstart-evidence` archive instead of rebuilding it.

The same test on another OS/architecture, with published contracts, another
database mode or another production binary is a distinct context and remains.
Prepared product releases use the shared validation bodies in their own complete graph.
Explicit manual build-only publication keeps its existing behavior.
Legacy component workflows remain callable but are absent from the main call
graph, so their old job bodies do not repeat main checks.
