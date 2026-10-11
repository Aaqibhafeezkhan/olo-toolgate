<!-- GENERATED FILE: tools/contracts/version.py; do not edit -->
# OLO ToolGate 0.10.0-dev release notes

Contracts version: `1.0.0`.

License: Apache-2.0. See LICENSE and bundled third-party notices/SBOMs.

# Changelog

All notable project changes should be documented here.

The project follows semantic versioning once stable versioning begins.

## 0.10.0-dev

- The Java standalone runtime serves the MCP Tasks extension (M0 part 4, adapter A2).
  Async tools run as tasks for requests that declare `io.modelcontextprotocol/tasks`:
  `tools/call` returns a `CreateTaskResult`, and `tasks/get`, `tasks/update` and
  `tasks/cancel` follow SEP-2663. `requestInput()` maps to `input_required`,
  `progress()` to the status message, cancellation has a 5 s grace, `cancelIfAbandoned`
  cancels after 3 missed polls, and tasks are owner-bound and purged after their TTL.
  Tasks live in a memory store behind the `TaskStore` port. A `-32021` error now carries
  `data.requiredCapabilities` as a client capabilities object, as the 2026-07-28 schema
  requires, and the stdio era lock is specified as `-32022`, matching case
  `legacy/stdio-era-lock`.

- The Java Tool SDK runs tools standalone (`tool-sdk/java-sdk`, M0 part 3).
  `toolgate-protocol` serves MCP 2026-07-28 over stdio (per-request `_meta`, the stdio
  era lock, `server/discover`, `tools/list`, `tools/call`), and
  `toolgate-sdk-runtime-mcp` dispatches calls to annotated handlers with input and
  output schema validation, size limits, `maxConcurrency`, deadlines, the errors.md
  error model and redaction. It refuses at startup what it cannot serve yet: client
  tools, business-key tools, and identity tools in production. Tasks, the identity
  verifier, the legacy 2025-11-25 path, Streamable HTTP and the route client come in
  later parts. `@Param` is now retained at run time, and the processor's
  `package.json` carries each tool's declared error `dataSchema`s.

- The Tool SDK has its Java authoring API and descriptor processor (`tool-sdk/java-sdk`,
  M0 part 2). `toolgate-sdk-api` holds the annotations of authoring/java.md,
  `ToolContext` and the error hierarchy; `toolgate-sdk-processor` turns them into
  generation-1 semantic descriptors at compile time, writes their canonical bytes to
  `META-INF/toolgate/tools/`, and fails the build on the declaration rules. It needs
  `-Atoolgate.descriptorGeneration=1`; there is no default. It reproduces descriptor
  vectors tool-01, tool-02 and tool-03 byte for byte, and the set-01 digest.

- The Tool SDK has its packager: the `toolgate` CLI (`tool-sdk/cli`, M0 part 1). It
  computes canonical descriptor bytes and the `toolDescriptorDigest`,
  `descriptorSetDigest` and `packageDigest`, compares descriptors under the equivalence
  rewrites, and packs, validates and verifies `.tgpkg` archives offline under the
  archive rules of plan.md §7.6. It passes the descriptor vectors, all 20 package
  archive fixtures and the v2 schema fixtures. The archive fixtures' stub manifest now
  names the artifact digest `artifactDigest`, as `PackagedArtifact` v2 does.

- The contract set is `1.0.0` (v2-generation.md decision 7). The v1 schemas now use the
  v2 request context: `RequestContext`, `CredentialKind`, `RequestMode` (was
  `EnterpriseRequestMode`) and `ActorHop` (was `EnterpriseActorHop`) are defined only in
  `schemas/v2/common.schema.json`, and v1 models such as `EnterpriseContext` reference
  them there. The wire format is unchanged. Bindings name them in the v2 namespace
  (`olo_toolgate_contracts::v2`, `io.ololabs.toolgate.contracts.v2`, `V2` in TypeScript
  and PHP); the v1 copies are removed. The Rust `CANONICAL_SCHEMAS` inventory embeds
  both sets so v1 validators resolve the v2 references offline.

- Agents and agent groups have an optional route slug (`slug`, lowercase letters,
  numbers and hyphens), unique among agents and among agent groups. It names them in the
  scoped MCP routes planned for M2 (`/mcp/agent/{slug}`, `/mcp/agent-group/{slug}`), and
  the console edits it as **Route slug**.

- The Gateway's request context no longer names a device (ADR 015 amendment). Control
  places each call on one online device of the execution binding's device group: the
  caller's own device first, otherwise the member with the fewest queued calls, so a
  pool of executor containers registered as devices shares the work. A replay keeps its
  device. Mounted Gateway credentials drop `deviceId` and must carry
  `credentialKind: "STATIC_TOKEN"`; the Gateway fills in `expiresAtUnixMs`.

- The console has a **Configuration** menu (plan.md §18): Server (gateway name), Device
  (device approval and the read-only check-in values) and Backup/Restore (configuration
  import/export and the server settings file). Each page saves only its own group with
  `PATCH /api/control/v1/settings`; a stale revision reloads the current values. Audit >
  Configuration reviews keeps only the review queue, now at `#config-reviews`; old
  `#configuration` links redirect. Server settings are formatVersion 2 (grouped, with
  `clientCheckIn`); formatVersion 1 documents and files are no longer accepted, and
  saved v1 settings reset to the defaults.

- Client check-in is configurable (plan.md §17.2): `TOOLGATE_CONTROL_CLIENT_CHECKIN_MS`,
  `TOOLGATE_CONTROL_CLIENT_CHECKIN_JITTER_PCT` and `TOOLGATE_CONTROL_CLIENT_OFFLINE_AFTER_MS`,
  set by Helm `control.clientCheckIn.*`. Quickstart keeps 500 ms; other deployments now
  default to 2 s with 20 % jitter. The burst guard and the offline threshold follow the
  configured values, and Control refuses values that leave no room for a client job
  inside the 30 s sync deadline. The v2 invocation task migrations (Flyway V18,
  Quickstart V11) now ship in the product.

- The Gateway's `/mcp` route serves only MCP 2026-07-28 (ADR 018 amendment). `ping` is
  no longer answered, and session-era requests (`initialize`, or an older
  `MCP-Protocol-Version` without a `_meta` version) get -32022 listing `2026-07-28`.

- A fresh Windows install enrolls with the gateway it was downloaded from. Uninstall
  parks the focused gateway's enrollment in its profile, and setup parks an enrollment
  an older uninstall left behind (or retires it when that gateway was recreated), so the
  earlier gateway stays connected in the background instead of being inherited.

- Windows setup downloaded from a console installs and connects in one step: it skips
  the gateway and repair/uninstall questions when the download names its gateway, and
  enrollment no longer reports an error when a reinstall resumed an enrollment.
  Uninstall is complete: it removes every gateway enrollment, logs, local tool settings
  and remembered gateways and CAs (`uninstall --purge`). Only the device key stays, so a
  gateway that already approved the device reconnects it without a new approval.

- Connect in the console starts a fresh enrollment when the client is already pointed
  at that gateway but its enrollment no longer works (revoked, or offline after an
  immediate check-in), including after switching back to a gateway that has forgotten
  the device (`reenroll` command). A device the gateway already approved recovers its
  identity and turns green without a new approval. The console retries Connect once on
  its own when, after Connect, the client is on another gateway or still not accepted.

- Keep the Windows client connected to every gateway it has enrolled with. Switching
  gateways moves focus (and the tray icon color) instead of disconnecting; Show status
  has a Status and Activity log tab per gateway headed with its name and URL. Gateways
  send their name, initialized from `TOOLGATE_GATEWAY_NAME` and editable under
  Configuration, in enrollment and check-in replies.

- Add a live branded Windows tray status window, command activity/progress,
  protected bounded device history and an About/version menu. Require license
  acceptance in the interactive Windows installer.

- Consolidate enterprise completion into sequential source, native, browser,
  Gateway, PostgreSQL, restore, deployment, SDK and image checks. Trace all 96
  acceptance scenarios to named evidence, retaining missing or skipped gates.
  Make Linux custody, PostgreSQL runtime and reviewed restore mandatory in CI;
  keep Quickstart alive through dependency health-check outages.

- Enterprise intermediary checkpoint: replace individual ACLs and local ALLOW with
  complete group grants, live Control authority, single-use bound effect permits,
  durable budgets and outcomes, scoped runtime secret delivery, independent
  configuration/reconciliation review and signed restore quarantine. Add migration
  shadow evidence, production replica checks and current group administration UIs.
- Remove retired active contract models, static-policy setup and obsolete test
  producers. Older development clients/configuration writers must be upgraded;
  legacy authority is archived and requires explicit reviewed group conversion.
  Earlier entries below describe historical features superseded by this change.

- Verify automatic membership audit transactions in HTTP smoke and SQLite tests,
  give the Compose test Gateway a readiness probe, and handle local Quickstart
  images without attempting registry pulls.

- Gate CI builds on release metadata and generated bindings, with regression tests
  for stale release notes, missing files, newline drift and version changes.

- Manage named roles with fixed capability templates and JSON device/tool scopes,
  direct and team-inherited assignments, guarded role changes, retained-state
  migrations, grouped console submenus and administrative audit browsing.

- Add directory editors, disabled registration defaults, transactional default-team
  and policy membership, multiple teams, administrative roles and combined privilege
  templates with device-group policy scopes and last-Super-Admin protection.

- Match shared OLO logo branding and favicon, default to dark with saved
  Dark/Light/System themes, and expand login/client downloads to a responsive
  full-width layout with accessible theme palettes.

- Fix Quickstart Tool Builder and Packages for the persistent default administrator
  in both login modes: verified internal TLS, dedicated fleet/release signing
  custody, and bounded local content-addressed package downloads.

- Add managed Compose stacks with default/external PostgreSQL, authenticated Redis,
  and an isolated password-free local Quickstart. Quickstart supports bounded
  embedded/external Redis catalog caching and a real external PostgreSQL adapter.
- Keep password login enabled by default; explicit local mode auto-enters the UI
  while signed API sessions, Gateway policy and ASK remain enforced. Add option
  verification, PostgreSQL/vault restart and browser CI smoke with runtime notices.

- Add unsigned Windows setup, macOS disk-image installer and Linux service launcher
  for x64/ARM64, checksummed anonymous downloads and native installer CI smoke.

- Publish development binaries, tested Docker Hub images and uniquely versioned
  Maven contracts after all same-commit main CI gates pass.
- Include generated release notes, Apache-2.0 license and project notices in native
  archives, contract libraries and containers; preserve dependency notices/SBOMs.

- Add the one-image non-HA Quickstart with real Gateway/Control/console,
  transactional SQLite, persistent `/data`, forced password change and separate
  generated identity/policy/permit/device/vault keys.
- Add protected built-in execution, exact-scope ASK, encrypted name-only vault,
  verified anonymous Windows/macOS/Linux downloads and direct mTLS enrollment.
- Add checksummed offline backup/restore, schema upgrade tests, production/debug
  runbooks and protected image publication with scans, SBOM and provenance.

- Add custom local tool authoring with code/runtime/schema/AI metadata,
  permission/resource/credential declarations and bounded secret scanning.
- Add durable signed designated-client sandbox tests and immutable organization
  packages, independent offline release signing, deployment and publication preparation.
- Add fixed inline Python/Node/PowerShell/Shell runners inside existing OCI
  confinement; Control never executes author code. Native platform certification
  limits remain explicit in the Module 10 report.

## 0.9.0-dev

- Add independent signed package releases, device-scoped desired generations and
  mTLS artifact grants backed by immutable external HTTPS descriptors.
- Add durable canary assignments, reported-state aggregation, update/rollback and
  uninstall with atomic client activation after confined runtime health checks.
- Add Packages console, external signing/store Helm references, production/debug
  runbook and lifecycle/security gates. Native execution certification limits from
  Module 08 remain explicit; no unsupported host runtime fallback is introduced.

## 0.8.0-dev

- Add canonical JSON local invocation and IPC revision 3, managed image preparation,
  fixed runtime vectors, online Gateway binding and disposable OCI resource isolation.
- Add first-use interpreter provisioning from approved immutable images; host Python
  is unnecessary. Document external engine, Batch/CMD/WASM and native CI limitations.
- Add malicious real-runtime fixtures, health/counters, cleanup and runtime operations.

## 0.7.0-dev â€” Module 07

- Add protected HotFolder tools with capability-relative paths, no-follow links,
  extension/size limits, atomic writes, bounded events and fixed safe utilities.
- Require ready enrolled identity and fresh Gateway authorization on every call;
  bind copy/move to both paths and consume approved ASK permits online.
- Add anonymous Windows/macOS/Linux downloads, real native archive validation,
  checksum/SBOM/license packaging, six-target native service CI and release hooks.
- Preserve system-service operation across logout/lock; fix prerequisite enrollment
  timestamp, endpoint routing, TLS build configuration and Windows custody handling.
- See Module 07 verification for executed checks and outstanding release gates.

## 0.6.0-dev â€” Module 06 in progress

- Add canonical endpoint enrollment, discovery, identity, check-in and IPC models.
- Add Rust protected-service/CLI foundations, local key custody, bounded OS IPC,
  ordered durable reports and fail-closed offline/revoked health.
- Add Control enrollment/certificate issuer, browser confirmation, mTLS check-in,
  revocation, audit and PostgreSQL migration V5; add native build/package skeleton.
- Module 06 completion gates remain open; see its verification report.

## 0.5.0-dev â€” Module 05

- Add exact-operation ASK review with dedicated approver identity, one-time and
  temporary grants, deny, durable expiry, transaction races and audited state.
- Add format-2 signed ASK policies, runtime v2, separate Gateway RS256 permits and
  strict PostgreSQL-backed jti consumption. Approval outage/revocation blocks.
- Add approval console, external Helm keys/configuration, migration V4 and genuine
  contract, browser, production E2E, release compatibility and operational gates.

## Unreleased

### Module 04 â€” 0.4.0-dev

- Canonical v1 RS256/JWS policy bundles and deterministic four-language bindings.
- Bounded deterministic compilation, immutable PostgreSQL history, transactional
  publish/rollback/audit/idempotency, dedicated external signing key.
- Gateway verification, atomic snapshots, last-known-good, monotonic sequence,
  expiry and explicitly classified low-risk read grace; no unsigned fallback.
- Rotation/outage/restart compatibility tests, signed evaluator benchmark,
  external Helm key/token references and required cross-component release CI gate.

### Added

- Initial architecture and contributor documentation.
- Module 03 embedded React/TypeScript/Vite administration console, generated
  Control OpenAPI operation bindings, memory-only signed-token shell, bounded
  dashboard, paginated directory navigation and revision-aware user management.
- Component/API coverage, real signed-token PostgreSQL browser E2E, axe/keyboard
  accessibility gates, reproducible UI assets/notices/checksums and CI integration.

### Changed

### Fixed

### Security

## 0.3.0-dev â€” Module 02 Control Plane backend

Java 21/Quarkus tenant-scoped directory CRUD, typed identifiers and shared Control
schemas/bindings; PostgreSQL/Flyway, atomic audit/replay, retired identities and
optimistic revisions; signed administrative JWTs, safe JSON/YAML import/export,
bounded pages and private observability. Added non-root production image, external
PostgreSQL Helm resources, protected GHCR pipeline, real database/HTTP tests and
container/cluster smoke. No custom execution, policy publication or later module
is implemented.

## 0.2.0-dev â€” Module 01 gateway core

Stateless Rust authorization with validated static identity/policy, exact extraction,
deny precedence, acknowledged sanitized audit, bounded ingress, separate probes/
metrics, structured tracing and graceful shutdown. Added shared runtime types,
offline schema embedding and Gateway OpenAPI. Added non-root image, protected
GHCR workflow, gateway Helm workload, Kubernetes render/cluster smoke and benchmark.
No execution, permits, bundles, approvals or later module is implemented.

## 0.1.0-dev â€” Module 00 foundation

Canonical v1 contracts and deterministic Java/Rust/TypeScript/PHP bindings;
Gradle/Cargo workspace builds and local Java publication proof; fixture, drift,
license and security gates; foundation CI and protected release assets; empty
Helm skeleton with validated values. No runtime module or workload is included.
