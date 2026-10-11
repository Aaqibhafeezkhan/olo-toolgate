# ToolGate Tool SDK: final implementation plan

Status: final, after reviewing [plan.md](plan.md) (revision 10) and [publishing-plan.md](publishing-plan.md) (revision 3) against `olo-labs/olo-toolgate` at commit `8ea5091` (main, 2026-10-10).
This document says **what to build, in which order, and in which files**. The design itself stays in plan.md.

## 1. Verdict

**The plan holds, after six corrections made in revision 10.** The goals, the authoring contract, the package format, the egress model, effect safety, tasks and the publishing approach all fit the codebase. Six structural assumptions didn't match the code. Each was corrected toward what already exists:

| # | The plan assumed | The code does | Corrected plan |
|---|---|---|---|
| 1 | The Gateway signs permits from a policy snapshot | Control evaluates every call and issues permits (`/access/invocations`, `/reserve`); there is no offline ALLOW | Control issues Tool Host permits through the same path |
| 2 | A new `taskstore` with Gateway and Tool Host database roles | Control already has an invocation state machine (`control_enterprise_*` tables, nonces, budgets, reconciliations, outbox); the Gateway has no database access | Extend Control's tables; Gateway and Tool Host only call Control's API |
| 3 | A Gateway kill list with a grace period, plus Gateway break-glass | Nothing starts without Control | Kills enforced at Control; break-glass is a two-operator Control endpoint |
| 4 | `.tgpkg` delivered to devices with DSSE signatures | Devices accept only RS256 release JWS over `FleetPackageDocument` and digest-pinned OCI images, with no ZIP extraction | Control converts uploads; client tools ship as OCI images |
| 5 | A new package lifecycle | Fleet releases, the Builder flow and maker/checker configuration changes exist | Build on them |
| 6 | Several names and paths | Settings at `/api/control/v1/settings` with 409; Helm `control:`; `@olo-labs`; OpenAPI under `packages/contracts/openapi/`; `NATIVE`/`WASM` and "protocol v2" already used; two ADR 012 files | Names and paths corrected |

Every finding, with file and line references, is in plan.md §2.1.

## 2. Ground rules

- **Nothing is coded until design gates D0, D1 and D2 have passed** (plan.md §22.1). Phase 0 below is that design work. It produces files in the repository, but no product code.
- **Each milestone implements frozen artifacts and never changes them.** A needed change goes through an ADR amendment, which reopens the gate.
- **Every state change ships two migrations:** Flyway for PostgreSQL (`apps/control-plane/src/main/resources/db/migration`, next is V18) and the Quickstart SQLite runner (`db/quickstart`, next is V11, which needs the runner's version cap raised).
- **Every contract change updates all generated bindings** (Rust, Java, TypeScript, PHP), and the existing stale-binding check in `preflight.yml` stays green.
- **Existing invariants are kept unless an ADR says otherwise:**
  - no offline ALLOW;
  - Control is the only database writer;
  - no ZIP or TAR extraction on devices;
  - one remote job per client until Phase B;
  - v1 tool protocol remains supported.

## 3. Phase 0: design gates (produce the frozen artifacts)

### D0: Authoring contract

| Work item | Output | Location |
|---|---|---|
| Language API signatures for annotations and `ToolContext` in Java, Python, TypeScript and .NET, including routes, TCP, tasks, logging and the error hierarchy | One spec file per language plus a shared table | `tool-sdk/spec/authoring/` |
| Error model and execution semantics (plan.md §5.7) | Normative spec | `tool-sdk/spec/authoring/errors.md`, `execution.md` |
| Runtime modes, invariant, standalone identity (§5.8, §5.9) | Normative spec | `tool-sdk/spec/runtime-modes.md` |
| Descriptor generation 1 and `toolDescriptorDigest` rules (§5.6, §7.2) | Normative spec with canonicalisation test vectors | `tool-sdk/spec/descriptor/` |
| Library composition (§7.7) | Normative spec with fixtures | `tool-sdk/spec/composition.md` |
| Official SDK compatibility record | Move `sdk-compat/results-2026-10-10.md` and raw outputs; commit lockfiles for the pinned versions | `tool-sdk/spec/sdk-compat/` |
| Adapter contracts A1 to A5 (§5.10) | Interface definitions and acceptance cases | `tool-sdk/spec/protocol-layer.md` |
| ADR 019: SDK authoring API, runtime modes, compatibility | ADR | `docs/adr/` |

**Passes when** architecture review signs off the spec files and the adapter contracts.

### D1: Contracts

| Work item | Output | Location |
|---|---|---|
| Renumber the duplicate ADR 012 (`012-single-node-quickstart.md` → `012a`) and update references | Docs change | `docs/adr/` |
| v2 schemas: `PackageManifest`, `ToolDefinition`, `CatalogScope`, `InvocationPermit` (Control-issued, Tool Host audience), `Task` fields on invocations, `KillEvent`, `KillPush`, `ServiceProfile`, `DeploymentBinding`, business-key reservation, tool protocol v2 frames, `RequestContext` without device (keeping `credentialEpoch`, `expiresAtUnixMs`, `delegatedSessionEpoch`) | JSON Schema files with `$id` under `https://schemas.ololabs.io/toolgate/v2/` | `packages/contracts/schemas/v2/` |
| Valid and invalid fixtures for every v2 schema | Fixture files | `tests/fixtures/contracts/v2/` |
| Generator design for v2: namespaced bindings (`io.ololabs.toolgate.contracts.v2`, Rust module `v2`, TypeScript and PHP namespaces), a check that removes stale copied schemas (today `rust/schemas/v1/approval.schema.json` has no canonical source), and the contract-set version moving to 1.0 | Design note | `docs/contracts/v2-generation.md` |
| OpenAPI changes: `PATCH /api/control/v1/settings` (merge-patch, If-Match, 409); package upload, review and enable; deployment bindings and effective values; Tool Host registration, `permits/consume`, `effects/report` and secret delivery for Tool Host identities; kill and break-glass; Gateway `/mcp` scoped routes | OpenAPI files | `packages/contracts/openapi/{control,gateway,quickstart}-v1.yaml` |
| Check-in variables `TOOLGATE_CONTROL_CLIENT_CHECKIN_MS`, `_JITTER_PCT`, `_OFFLINE_AFTER_MS`; Helm `control.clientCheckIn.*` | Spec | `docs/control-plane/configuration.md` (spec section) |
| Configuration menu spec (§18): sections, routes, redirect for `#configuration`, `formatVersion` 2 | Spec and wireframe | `docs/admin-ui/configuration-menu.md` |
| `ROADMAP.md` rewritten to point at plan.md §22 | Docs change | `ROADMAP.md` |
| ADRs 014 (package format, conversion to fleet releases, lifecycle), 015 (credential classes, catalog scope, routes), 018 (MCP versions and legacy adapter), 020 (deployment binding), 021 (check-in and Configuration menu) | ADRs | `docs/adr/` |

**Passes when** architecture review signs off, and the existing contract checks (`preflight.yml`) accept the new schema files.

### D2: Trust and persistence

| Work item | Output | Location |
|---|---|---|
| Threat model for Tool Host, broker, egress proxy, transport matrix, Tool Host workload identity, and Tool Host secret delivery | Threat model | `docs/security/threat-models/tool-host.md` |
| Persistence specification: the §12.7 transitions mapped onto Control's existing states (`PENDING_APPROVAL → QUEUED → RESERVED → EXECUTING → …`), new columns and tables (task fields, business-key unique index, result payloads above 64 KiB, lease heartbeat), with the exact Flyway and Quickstart migration contents | Spec | `docs/control-plane/invocation-state-v2.md` |
| Kill events: storage, authorization-epoch bump, push protocol, break-glass endpoint with 2-of-3 operator keys | Spec | `docs/control-plane/kill-switch.md` |
| ADR 013 (Tool Host, egress, broker, secrets, transports; supersedes the Gateway permit-signing parts of ADR 006; ADR 005 is unchanged), ADR 016 (tasks, effect safety, business keys, persistence), ADR 017 (kill switch) | ADRs | `docs/adr/` |

**Passes when** security review signs off the threat model and the persistence and kill specs.

### D3: Conformance cases authored

The cases for every suite in plan.md §21 are written under `tool-sdk/conformance/` and `tests/`, not yet passing. They must pass before any milestone exits.

## 4. Coding milestones

Once D0 to D2 have passed, M0 and M1 run in parallel. M2 needs M1, and D3 must also pass before M2 can exit.

```text
Phase 0:  D0 ─┐
          D1 ─┼─► M0 SDK standalone preview ─────────────► M5 governed Python/TS/.NET
          D2 ─┘   M1 contract implementation ─► M2 foundation ─► M3 adoption ─► M6 distribution
          D3 (before any exit)                       └──────────► M4 enterprise reliability
```

### M1: Contract implementation (existing components)

| Component | Work | Files |
|---|---|---|
| Contracts | Implement the v2 generator design; generate Rust, Java, TypeScript and PHP bindings; stale-copy check | `tools/contracts/generate.py`, `packages/contracts/{rust,java,typescript,php}` |
| Gateway | `RequestContext` v2 without device; legacy MCP adapter for 2025-11-25 and 2025-06-18 (accept `initialize` and notifications on the legacy path; keep `ping` there only); MCP version model | `apps/gateway/src/{application.rs,mcp.rs,http.rs,relay.rs,auth.rs}` |
| Control | `RequestContext` v2 consumers; agent and group slugs; `PATCH /settings` with `formatVersion` 2, 409 on conflict, env-owned fields excluded from `ConfigurationImportBootstrap`; configurable check-in with the burst guard scaled to the interval | `adapter/ControlResource.java`, `ServerSettingsService.java`, `EndpointService.java:190,208,226`, `application.properties`, Flyway V18, Quickstart V11 |
| Helm and Quickstart | `control.clientCheckIn.*` values and schema; env defaults 500 ms (Quickstart) and 2000 ms (enterprise) | `deploy/helm/olo-toolgate/{values.yaml,values.schema.json,templates/control.yaml}`, `deploy/compose/QuickStart*/compose.yaml` |
| Console | Configuration group with Server, Device and Backup/Restore pages; review page keeps only the queue; `#configuration` redirect | `apps/admin-ui/src/{App.tsx,ServerSettings.tsx,ConfigurationRequests.tsx,ConfigurationTransfer.tsx,api.ts}` plus new page files |

**Exit:** contract suite green against the D1 fixtures; `gateway.yml`, `control.yml`, `quickstart.yml` and `preflight.yml` green; existing e2e tests unchanged and passing.

### M0: SDK standalone preview

| Work | Files |
|---|---|
| `tool-sdk/` layout: `spec/`, `conformance/`, `cli/` (Rust crate, added to the Cargo workspace), `java-sdk/`, `python-sdk/`, `typescript-sdk/`, `dotnet-sdk/`, `docs/`, `examples/` | Root `settings.gradle.kts`, `Cargo.toml` and `package.json` registrations |
| Per language: authoring API, descriptor generation, error model, standalone runtime with local task and reservation stores, routes HTTP client, testing library | Per SDK module (publishing-plan.md §3) |
| Protocol layer per language. A2 Tasks, A3 `requestState` and A4 identity are needed in all four; A5 routing in .NET and Java; A1 protocol core in Java only | `tool-sdk/<lang>/protocol` |
| `toolgate` CLI: `init`, `dev`, `validate`, `pack` (offline, using the contracts crate) | `tool-sdk/cli` |
| Starters, examples 1 to 4 and 9, docs site, release workflows modelled on `release-foundation.yml` | `tool-sdk/examples`, `tool-sdk/docs`, `.github/workflows/sdk-*-release.yml` |

**Exit:** official SDK compatibility suite and the standalone side of mode equivalence pass, and a first tool takes under 10 minutes in each language.

### M2: Foundation (governed execution)

| Component | Work | Files |
|---|---|---|
| **New `apps/tool-host`** (Rust) | Registration with an mTLS workload identity; admission (verify Control permit, `permits/consume`); sandbox runtime (shared crate extracted from the client's engine); per-sandbox netns; broker with routes and the service profile; CONNECT egress proxy with the transport matrix; secret redemption through Control; local result outbox; audit spool with headroom and `FENCED`; control stream for kill pushes | `apps/tool-host/`, new `crates/sandbox` (from `apps/endpoint-client/src/execution/engine.rs`) |
| Gateway | Scoped routes `/mcp/agent-group/<g>`, `/mcp/agent-group/<g>/<a>`, `/mcp/agent/<a>` (router and guard); JWT and introspection credential classes (JWKS cache only, no decision cache); `mcpName` and aliases; catalog revision; Tasks fields in the parser; `subscriptions/listen` (HTTP server changes for streaming); `Idempotency-Key` and business keys mapped to `downstreamIdempotencyKey`; mTLS dispatch to Tool Host; audit spool | `apps/gateway/src/*`, `apps/gateway/src/config.rs` schema |
| Control | Tool Host registry and pools; Tool Host identity on `permits/consume`, `effects/report` and secret delivery; permits addressed to Tool Hosts; invocation extensions (task fields, business-key unique index, result payloads, lease heartbeat); `.tgpkg` upload and verification; conversion of client-tool packages to fleet releases (JWS + OCI image); enable as a maker/checker configuration change, auto-approved in Quickstart; `DeploymentBinding` and effective values; `ServiceProfile` storage and security delta; kill events, epoch bump, push and break-glass; per-agent and per-tool quotas on execution budgets | `apps/control-plane/src/main/java/...`, Flyway V19 onward, Quickstart V12 onward |
| CLI and Java SDK | `toolgate sign` and `upload`; governed Java runtime and `toolgate-launch`; OCI image build for client tools | `tool-sdk/cli`, `tool-sdk/java-sdk/runtime-host`, base images |
| Endpoint client | Tool protocol v2 codec next to v1; kill push handling; client tools from SDK packages arrive as ordinary fleet releases | `apps/endpoint-client/src/{execution/adapters.rs,execution/mod.rs,service.rs,socket.rs}` |
| Console | Upload, review (API diff and security delta), enable, deployment binding, kill switch | `apps/admin-ui/src/` new pages |

**Exit:** a Java server tool and a Java client tool run end to end through `/mcp` and the scoped routes. Security, duplicate-execution, kill, revocation and §12.7 failure-injection suites pass.

### M3: Adoption

MCP proxy and OpenAPI import executors in the Tool Host broker trust domain; registration, snapshot and review flows in Control; review pages in the console. **Exit:** an unmodified MCP server and a REST API are governed through ToolGate.

### M4, M5, M6

- **M4, enterprise reliability:** task UI and `cancelIfAbandoned`; recovery automation; break-glass tooling; connections delivery (§14.3); client Phase B (persistent socket, per-runtime concurrency, adoption deltas on `EndpointAdoption.revision`).
- **M5, multi-language governed:** governed runtimes for Python, TypeScript and .NET.
- **M6, distribution:** Marketplace modules 12 to 15, cohort rollouts on fleet releases, public registry publishing, and the DEFERRED items once their ADRs are accepted.

## 5. Tests and CI

| Suite (plan.md §21) | Runs in |
|---|---|
| Contract and descriptor, route analysis, composition, digests | `preflight.yml` (extended) |
| Gateway protocol and security cases | `gateway.yml` |
| Control persistence, kill and quotas; both migration trees | `control.yml` |
| Tool Host security, egress and failure injection | new `tool-host.yml` |
| SDK unit, official SDK compatibility, mode equivalence | new `sdk-*.yml` |
| End-to-end through Quickstart | `quickstart.yml` |

Every workflow keeps the repository's conventions: `admission.yml` gating, SHA-pinned actions and protected release environments.

## 6. Implementation risks

| Risk | Mitigation |
|---|---|
| Control load rises, since every server-tool call makes 2–3 Control round trips | Load test in M2 exit; Control replicas and PostgreSQL sizing are deployment checks (plan.md §8.5) |
| The Quickstart SQLite runner and the hand-written duplicate migrations drift | One migration spec per change in D2; a CI check compares both trees' resulting schemas |
| Java has no official 2026-07-28 support | A1 adapter with the 37 conformance scenarios as acceptance; retire it when an official release passes |
| The v1 to v2 contract move touches every component | M1 lands it in one release with the generator, bindings and all consumers together |
| The Tool Host is a new security boundary | D2 threat model, blocking security suite, and the sandbox crate shared with the already-hardened client engine |
