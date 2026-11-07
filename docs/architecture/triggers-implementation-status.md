<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Triggers implementation status

This is the live progress document for issue #274 (server and client triggers). The design is
[`device-triggers-plan.md`](device-triggers-plan.md) (revision 12). Module scope, weights, dependencies and
*Done when* criteria come from its section 24.2; nothing here changes them silently.

## Overall progress

Updated: **2026-11-07**.

**Overall: 0% complete; 100% pending.** 0 of 18 modules merged. Module 1 is implemented and verified, TLC
included, and awaits gate D0 review in its PR (see Module 1 evidence). Module 2 (Java SDK, POLL) is implemented and
verified in its PR, which is based on module 1's branch until that merges (see Module 2 evidence). Module 4 (Control
core for client triggers) is implemented and verified in its PR, stacked on module 2's branch (see Module 4 evidence).

| Slice / gate | Needs modules | Status |
|---|---|---|
| Slice 1 — client POLL trigger end to end (35%) | 1–5 | ⏳ Pending |
| Slice 2 — server trigger with failover (56%) | 1–8 | ⏳ Pending |
| Slice 3 — schedule and device trigger → authorized MCP (85%) | 1–14 | ⏳ Pending |
| Slice 4 — composite MCP from an agent and an action (92%) | 1–15 | ⏳ Pending |
| Release-ready (99%) | 1–17 | ⏳ Pending |
| Gate G-STATE (TLC model, stateful property tests, deterministic simulation) | 1, 3, 17 | 🔵 Item 1 (TLC) passes at the bounds in *Baseline changes*; items 2–3 pending |
| Gate G-IFC (conformance and adversarial corpus, security sign-off) | 11, 16 | ⏳ Not run |
| M12 end-to-end scenarios | all | 0 of 58 passing |

## Module progress

| # | Module | Track | Weight | Status | Earned | PR |
|---|---|---|---:|---|---:|---|
| 1 | Contracts, ADR and model | A | 5% | 🔵 In progress | 0% | olo-labs/olo-toolgate#281 |
| 2 | Java SDK, POLL | B | 6% | 🔵 In review | 0% | olo-labs/olo-toolgate#282 |
| 3 | Supervisor crate | C | 8% | ⏳ Pending | 0% | — |
| 4 | Control core for client triggers | D | 8% | 🔵 In this PR | 0% | — |
| 5 | ★ Client integration and ingest | E | 8% | ⏳ Pending | 0% | — |
| 6 | Lifecycle, rollout and raw page | D | 6% | ⏳ Pending | 0% | — |
| 7 | PUSH mode and file watching | B/E | 7% | ⏳ Pending | 0% | — |
| 8 | ★ Server triggers on the Tool Host | E | 8% | ⏳ Pending | 0% | — |
| 9 | Python SDK parity | B | 4% | ⏳ Pending | 0% | — |
| 10 | Event router and retention | D | 4% | ⏳ Pending | 0% | — |
| 11 | Information flow, coarse mode | D | 3% | ⏳ Pending | 0% | — |
| 12 | Subscriptions and WebSocket | D | 5% | ⏳ Pending | 0% | — |
| 13 | Schedules (Quartz) | D | 5% | ⏳ Pending | 0% | — |
| 14 | ★ Actions | F | 8% | ⏳ Pending | 0% | — |
| 15 | ★ Composite MCP | F | 7% | ⏳ Pending | 0% | — |
| 16 | Information flow, field-level | F | 4% | ⏳ Pending | 0% | — |
| 17 | Resource safety and release gate | F | 3% | ⏳ Pending | 0% | — |
| 18 | IMAP PUSH sample | — | 1% | 🚫 Blocked (Tool Host TCP tunnels, ADR 013) | 0% | — |
| | **Overall** | | **100%** | | **0%** | |

## How progress is counted

- The module weights from plan section 24.2 sum to **100%**. They are planning estimates, not measured effort.
- A module earns its whole weight **only when its PR is merged** with its *Done when* evidence recorded below.
  In-progress modules and open PRs earn 0%. Partial modules earn nothing.
- A PR's table shows `main` as it is, with that PR's module as **🔵 In this PR** and the overall value before and
  after the merge. When two parallel PRs are open, the one merged second rebases and recalculates its table.
- A scope or weight change (for example, module 16 moving after the release, or module 18 staying blocked) is
  recorded in *Baseline changes* with the old value, the new value and the reason, in the same PR. The denominator is
  never changed silently.
- A slice or gate is ✅ only when its own evidence has run, not because its modules are merged.

## Baseline changes

The baseline is plan revision 12, section 24.2. Module weights are unchanged; the scope moves below are recorded with
their reasons.

| Date | Change | Old | New | Reason |
|---|---|---|---|---|
| 2026-11-07 | Module 4 scope: package review and enable (plan 10.2 "existing review / enable routes") | Definitions are the triggers of enabled package versions; the existing review flow carries the security delta | Definitions are the trigger exports of every uploaded (`VERIFIED`) package version, each with a trigger security delta against the version uploaded before. Weights unchanged | Control has no review or enable state for SDK packages today (versions stay `VERIFIED`; tools deploy straight from them), so there is no existing flow to extend. Gating trigger use on a review step belongs with that flow when it exists |
| 2026-11-07 | Module 4 scope: plan 10.1 conventions | Errors are RFC 9457 `application/problem+json`; bodies may be JSON or YAML | Errors use Control's existing error envelope with the same status codes (404 identical for unknown and cross-tenant, 412 stale ETag, 428 missing If-Match); bodies are JSON only | One error format per Control API: the console and every client parse the existing envelope. Changing it is a Control-wide decision, not a trigger one |
| 2026-11-07 | Module 4 boundary: what later modules wire | — | Server targets are refused (module 8); lifecycle overrides and actions (module 6); leases reach devices through `TriggerSyncAck` in check-in (module 5), which calls `TriggerService.leases`; the `triggers.v1` capability gate on the desired route arrives with check-in negotiation (module 5) | Each is owned by its module in plan 24.2; module 4 builds and tests the Control side they call |
| 2026-11-07 | Contract amendment for module 4 (ADR 058, *Amendment (triggers module 4)*) | No admin resource schemas; `FleetSignatureHeader.typ` has three types | Assignment, instance and definition resources in `trigger.schema.json`; `toolgate-trigger-desired+jws` | Module 1 left the admin resources to module 4 (its evidence says so); the desired document needs its own signature type. Edited in place and regenerated |
| 2026-11-07 | Module 2 scope: server-trigger capabilities (plan 5.6, 5.8) | Module 2 builds `PollContext.routes()`, `tcp()` and `secrets()` for `@ServerTrigger`, and the processor accepts their declarations | The processor refuses routes, TCP destinations and secrets on triggers; `PollContext` has none of them. Module 8 (lease-bound broker and secrets, sample `mail-watch`) adds them. Weights unchanged (6% and 8%) | The module 1 contracts cannot carry them yet: `TriggerDescriptor.routes`, `tcpDestinations` and `secrets` are id lists without the route definitions a broker validates calls against, and `TriggerStartFrame.secretHandles` has no name-to-handle mapping. Building the SDK side now would fix a broker contract before module 8 designs it |
| 2026-11-07 | Contract amendment for module 2 (ADR 058, *Amendment*) | `PackageManifest` has no trigger exports; `descriptorSetDigest` covers tools | `PackageManifest.triggers` (`TriggerDefinition`) and `triggers/<name>.json`; `descriptorSetDigest` covers tools and triggers; Control refuses packages with `triggers` until module 4 | `toolgate pack` support for trigger exports (module 2 *Done when*) needs them. Edited in place and regenerated (fresh build) |
| 2026-11-07 | G-STATE item 1 bounds (plan 6.6.1) | TLC model-checks 3 events, 2 epochs, 2 executors | Exhaustive at 2 events, 2 epochs, 2 executors, with crash and failover in separate SKIP and lifecycle configurations (HALT combines every core fault); every fault combined at 3 events by a fixed-seed random simulation | With every fault combined the search did not finish: at 3 events, HALT still had a growing queue after 170 million distinct states (20 minutes; an earlier 3-event run filled about 12 GB of state files); at 2 events, SKIP after 244 million (30 minutes) before the state reductions below and after 46 million (7-minute cap) with them. Crash alone or failover alone completes at 2 events in seconds to minutes. The simulation is evidence, not proof, at the original bound. |

## Module evidence

Each merged module records: delivered scope against its *Done when*, the checks actually run with their results
(including skips and failures), the M12 scenarios that now pass, shared-file changes, #230 coordination, and known
limits. Results are never invented; a check that was not run is listed as not run.

### Module 1 — Contracts, ADR and model (in progress)

Scope: ADR 058; `v2/trigger.schema.json` with protocol frames and Trigger Sync messages; generated Java, Rust, PHP
and TypeScript bindings; fixtures; proposed OpenAPI; descriptor keyword amendment; `docs/specs/trigger_delivery.tla`.

Done when: gate D0 approved; contract round-trip tests pass; the check-in and ack worst-case size contract test
passes (plan 8.5); TLC passes on the model (plan 6.6.1).

**Implemented so far** (branch `feature/triggers-m01-contracts`, based on `main` at `bb3b078d`):

- [ADR 058](../adr/058-triggers.md) and the trigger schema `packages/contracts/schemas/v2/trigger.schema.json`
  (74 definitions): descriptor, the 14 trigger-mode stdio frames, CloudEvents envelope and producer record, signed
  desired document, `TriggerSync` and `TriggerSyncAck` with snapshot paging, and the chunked checkpoint transfer.
- `EndpointCheckIn.triggers` and `EndpointCheckInAck.triggers` reference them. Control refuses a check-in that carries
  `triggers` until ingest exists (module 5); it never ignores it. `Digest` now declares its exact length (71).
- Generated bindings for Java, Rust, TypeScript and PHP; valid fixtures for every new definition and 19 invalid
  cases; six executor routes in the proposed Control OpenAPI (desired document and checkpoint transfer).
- `tests/contracts/test_trigger_contracts.py`: every trigger string, array and opaque value is bounded, the longest
  value of every definition is valid, and the worst-case check-in and ack fit their budgets for 32 and 256 instances.
- `docs/specs/trigger_delivery.tla` (transitions T1–T15, invariants S1, S3, S4, L, A, C, D, P2, F, M, X, I, Q,
  action properties P and R, liveness `EventuallyStored`) with nine configurations, and
  `tools/triggers/model_check.py`, which runs the seven exhaustive ones and the two 3-event simulations and keeps TLC
  state files under the git-ignored `.dev/tlc`. The plan named the file `trigger-delivery.tla`; TLA+ module names
  cannot contain `-`, so it is `trigger_delivery.tla`. S2 (a retryable event holds the barrier) has no separate
  invariant: a retryable rejection is an item that stays pending, and L and P2 forbid any checkpoint passing it.
- Model corrections found by TLC: distrust requires that the lease has already moved and a distrusted executor never
  receives it again (L violation); R is an action property, because an upgrade that discards checkpoints legitimately
  resets accepted state; Q quarantines rows per producer, so a later trusted row of the same item stays deliverable;
  fairness is per record (producer, epoch, outboxSeq), because coarser fairness let Control re-ingest one duplicate or
  fenced copy forever (two liveness counterexamples).
- State reductions, each argued in the specification: a newer cumulative ack replaces an older one in flight;
  stopped process memory is reset; consumer hand-out is not modelled (Q is monotone in the queue); P is checked on
  each acknowledgement step instead of through a ghost variable; executors are symmetric in the safety
  configurations.

**Checks run** (Windows workstation, 2026-11-06 and 2026-11-07; TLC 2.19 from `tla2tools.jar` v1.7.4, 40 workers):

| Check | Result |
|---|---|
| `tools/contracts/generate.py --check`, `fixtures.py --check`, `tools/quality.py`, `tools/ci/preflight.py` | Pass |
| `tests/contracts` (181 tests) | 176 pass, 2 skipped. 3 fail identically on unmodified `main` on this machine: two need symlink privilege (WinError 1314), one scorecard observation |
| `tests/contracts/test_trigger_contracts.py` | Pass. Worst-case `TriggerSync` 166,735 bytes (limit 196,608); a Tool Host snapshot needs 26 pages of 10 instances |
| `tests/ci` (413 tests, `PYTHONUTF8=1`) | Pass, 10 skipped. Without UTF-8 mode, 8 release-notes tests fail on `main` too (Windows `charmap` decoding) |
| Java: `:contracts-java:test`; Control `EndpointTest`, `ContractDependencyTest` and `adapter.*` on an isolated PostgreSQL | Pass: 87 tests in 30 suites, 4 skipped |
| TypeScript contracts check and round trips | Pass |
| PHP 8.2 (Docker) round trips and lint | Pass: 298 v1 and 348 v2 round trips |
| Rust 1.94 (Docker): `cargo fmt --check`, contracts crate tests, `cargo check --tests` for the client and Tool Host | Pass |
| TLC `core_halt` (HALT, every core fault, 2 events) | Pass: 840,012 distinct states, depth 36 |
| TLC `core_skip_failover` (SKIP, failover, reinstall, loss, duplication) | Pass: 2,866,057 distinct states, depth 36 |
| TLC `core_skip_crash` (SKIP, crashes, loss, duplication, one epoch) | Pass: 35,217 distinct states, depth 25 |
| TLC `lifecycle_failover` (purge, upgrade, removal, reinstall, distrust, with failover) | Pass: 30,047,756 distinct states, depth 40 |
| TLC `lifecycle_crash` (lifecycle faults with crashes, one epoch) | Pass: 645,580 distinct states, depth 30 |
| TLC `liveness_failover` (`EventuallyStored` across failover and reinstall) | Pass: 2,151,974 distinct states, depth 36 |
| TLC `liveness_crash` (`EventuallyStored` across crashes and loss) | Pass: 70,434 distinct states, depth 25 |
| TLC simulation `core_skip_full` (3 events, every core fault, 10 minutes) | No violation: 365,972,388 states checked on random behaviours up to depth 100 (seed 20261107) |
| TLC simulation `lifecycle_full` (3 events, every fault, 10 minutes) | No violation: 315,073,073 states checked on random behaviours up to depth 100 (seed 20261107) |

**Outstanding before the module is Done:** gate D0 review of the contracts and the delivery model in the PR. Admin resource schemas (`TriggerAssignment`, `TriggerInstance`) and the admin routes are part of module 4's
definitions and assignments API, not of this module.

### Module 2 — Java SDK, POLL (in this PR)

Scope: `@ClientTrigger` and `@ServerTrigger` in POLL mode, the processor's rules (plan 5.8) and the generated
descriptor (5.9), `PollContext`, the runtime loop on the trigger-mode frames (6.2), `toolgate check` and
`toolgate pack` for trigger exports, a test harness, and the sample `inbox-watch` in POLL form.

Done when: processor compile-error tests (5.8) pass; runtime frame tests pass; harness tests for the sample pass; the
package packs and verifies.

**Implemented** (branch `feature/triggers-m02-java-sdk`, based on module 1 at `1af150e3`):

- **API** (`tool-sdk/java-sdk/api`): `@ServerTrigger`, `@ClientTrigger`, `@TriggerExample`, `TriggerMode`,
  `SourceReplay`, `OverflowPolicy`, `TerminalRejectionPolicy`; package `io.ololabs.toolgate.sdk.trigger` with
  `PollContext`, `PollResult`, `TriggerEvent` and `Checkpoint` (an immutable JSON object).
- **Processor**: every rule of plan 5.8 that applies to POLL is a compile error: the `PollResult<E> check(C,
  PollContext)` shape on a public instance with a public no-argument constructor; public record config and event
  types; reverse-DNS `eventType` with a major version (and no `io.ololabs.toolgate.` prefix); `intervalMs` ≥ 5000,
  no `resyncIntervalMs` on POLL; `maxEventsPerHour` 1..3600; event data ≤ 16 KiB and checkpoints ≤ 16 KiB (128 KiB
  needs `SnapshotState`, module 7); `DROP_*` only with `lossTolerant` and `SKIP_AND_RECORD`; `@DeviceFileAccess`
  read-only and only on `@ClientTrigger`; no secrets, claims or globals on a trigger; one export namespace with tools;
  `mode = PUSH` refused until module 7. Each trigger becomes `META-INF/toolgate/triggers/<name>.json`, the
  `TriggerDescriptor` as RFC 8785 bytes, plus a `triggers` entry in `META-INF/toolgate/package.json`. A
  `@ServerTrigger` without capabilities gets `executors: ["CLIENT", "SERVER"]` (plan 5.7).
- **Runtime** (`TriggerRuntime`, selected by `TOOLGATE_EXECUTION_MODE=TRIGGER`): `TRIGGER_START` → config checked
  against the signed `configSchema` (≤ 8 KiB, defaults filled) → `READY` → a check at once and every `intervalMs`;
  `HEARTBEAT` every 10 s from its own thread; `EMIT` per event with process-scoped `emitSeq`; one `CHECKPOINT` per
  check, sent only after every emit of that check is resolved; `RETRYABLE_REJECTION` re-sends the same frame and
  `emitSeq` with full-jitter backoff; `TERMINAL_REJECTION` halts under `HALT` and resolves under `SKIP_AND_RECORD`;
  `RECONCILE`, `PAUSE`, `RESUME`, `STOP` → `EXIT`; `FILES_LIST/READ/INFO` broker frames for client triggers. Events
  are validated against `eventSchema` and `maxEventBytes` before anything is emitted. Every frame uses the generated
  bindings (`toolgate-contracts`, now compiled for Java 17 and published with the SDK).
- **Harness**: `ToolHarness.trigger(name)` / `TriggerHarness` run the real runtime over the real frames against a
  fake supervisor (strict binding parsing, scripted rejections, files faults, `runExamples`). `toolgate check`
  (`Checks`) runs every `@TriggerExample`; staging writes `triggers` into the source manifest and accepts packages
  with triggers only.
- **CLI** (`tool-sdk/cli`): `pack` writes `triggers/<name>.json` and `triggerDescriptorDigest`; `descriptorSetDigest`
  covers tools and triggers; `validate` checks digests, files, artifacts, the shared namespace and the trigger rules
  (executors, read-only device files, POLL/PUSH schedule, `DROP_*`, checkpoint size).
- **Contracts**: `PackageManifest.triggers` / `TriggerDefinition` (ADR 058 *Amendment*), regenerated for Java, Rust,
  TypeScript and PHP, with a valid fixture. Control's package validation refuses `triggers` until module 4.
- **Sample** `examples/java-tools/inbox-watch`: a `@ClientTrigger` POLL `STATE_SNAPSHOT` source with stable ids
  `file:<path>:<size>`, a pruned checkpoint, two `@TriggerExample`s and `InboxWatchTest`. The frame semantics the
  supervisor (module 3) relies on are summarised in the shared notes of this session group.

**Checks run** (Windows workstation, 2026-11-07; Rust 1.94 and PHP 8.2 in Docker):

| Check | Result |
|---|---|
| Processor `TriggerDeclarationTest` (every 5.8 rule as a compile error; descriptor bound strictly to the generated `TriggerDescriptor`; canonical bytes and index digest) | Pass: 12 tests. Whole processor module: 62 tests in 7 suites |
| Runtime `TriggerRuntimeTest` (frames on raw stdio, each parsed with its generated binding: barrier with out-of-order acks, retry with the same `emitSeq`, HALT and SKIP_AND_RECORD, STOP/EXIT, PAUSE/RESUME, starting checkpoint, failing and oversized checks, files broker, heartbeats, refused starts 65/78, protocol violation) | Pass: 14 tests. Whole runtime module: 115 tests in 15 suites |
| Harness `TriggerHarnessTest`; `ToolHarnessTest`, `ConformanceTest` | Pass: 5, 7 and 1 tests |
| Build support (`Checks` runs trigger examples; staging writes `triggers`) | Pass: 7 tests, 1 skipped (the CLI pack test needs a local `toolgate` binary, which this Windows machine does not have) |
| Gradle `check` for `contracts-java`, `package-archive`, `service-authorization`, `sdk-json` and every `sdk-java-*` module | Pass (`BUILD SUCCESSFUL`) |
| Sample `inbox-watch`: `InboxWatchTest` and `toolgateCheck` against the SDK published to the local Maven repository | Pass: 7 tests; both `@TriggerExample`s pass |
| Sample package: `toolgate pack`, `validate`, `verify` (CLI built in Docker, Rust 1.94) on the staged package | Pass; `triggers/new-file.json` packaged, descriptor valid against `TriggerDescriptor` through `PackageManifest`; packing twice gives identical bytes |
| Packed `artifacts/tool.jar` run with `java -jar` in trigger mode (`TRIGGER_START`, then `STOP`) | `READY`, `BROKER_REQUEST FILES_LIST`, `EXIT 0`; exit code 0 |
| Every Java example (`examples/java-tools`, `examples/mocked-tools`) `gradlew check` | Pass: 13 java-tools packages (69 tests, 4 skipped) and 7 mocked-tools packages (31 tests), every `toolgateCheck` passed |
| Rust (Docker): `cargo fmt --all --check`; `toolgate-cli` tests incl. new `tests/triggers.rs`; clippy `-D warnings` on the CLI; contracts crate tests; `cargo check --tests` for the client and Tool Host | Pass: CLI 43 tests (5 new), contracts crate 4 tests, clippy and checks clean |
| `tools/contracts/generate.py --check`, `fixtures.py --check`, `tools/quality.py`, `tools/ci/preflight.py` | Pass |
| `tests/contracts` (181 tests) | 176 pass, 2 skipped. 3 fail as on unmodified `main` on this machine: two need symlink privilege (WinError 1314), one scorecard observation |
| `tests/ci` (`PYTHONUTF8=1`) | 402 pass, 10 skipped. `test_focused_start_builds_and_starts_without_unrelated_suites` timed out (30 s PowerShell harness) while Gradle and Docker builds ran beside it; it passes when run alone |
| TypeScript contracts check, build and tests; PHP 8.2 (Docker) round trips and lint | Pass: 2 TS tests; 298 v1 and 349 v2 PHP round trips |
| Repository pre-commit script `tools/ci/precommit.mjs` (`PYTHON=py`) on the staged change: preflight, PR policy, extension tests, secret scan, UI check and tests, browser downloads | Pass |
| Control on an isolated PostgreSQL: `EndpointTest`, `ContractDependencyTest`, the package, marketplace, SDK-builder and server-package tests, `adapter.*`; `PackageArchiveTest` with the new trigger refusal | Pass: 145 tests in 38 suites, 4 skipped; package-archive 6 tests |

**Not run:** `examples/run-java-examples.sh` (it needs bash with a local `cargo`; its new trigger-only branch was
exercised by hand with the same `java -jar` command above); the Maven plugin end-to-end run (`tool-sdk/java-sdk/e2e`,
needs `mvn`); M12 scenarios (module 2 owns none).

**Outstanding before the module is Done:** review and merge after module 1 (gate D0). Known limits, by design of this
module: PUSH mode, `SnapshotState`, `nativeMount` and the `ACCEPTED` checkpoint level are module 7; server-trigger
routes, TCP destinations and secrets are module 8 (*Baseline changes*); `toolgate dev` does not serve triggers (they
have no MCP surface); the trigger `description` is in the package index, not in the signed descriptor, which has no
field for it — module 4's definitions API decides whether the contract needs one.

### Module 4 — Control core for client triggers (in this PR)

Scope: package acceptance of trigger exports and the security delta; the definitions API; `TriggerAssignment` CRUD
for client targets (ETag, idempotency, dry run); instances; the signed `TriggerDesiredDocument`; client leases;
permissions; audit; tenant tables.

Done when: API tests including cross-tenant negatives; desired-document signing and verification tests.

**Implemented** (branch `feature/triggers-m04-control-core`, stacked on module 2 at `1668ad26`; behind
`toolgate.triggers.control-core.enabled`, off by default):

- **Package acceptance:** `backend-libs/package-archive` validates trigger exports with the CLI's rules (digests,
  `triggers/` files, one export namespace, executors, read-only device files, POLL/PUSH schedule, `DROP_*`,
  checkpoint size) and the set digest over tools and triggers. Uploads accept them only while the module flag is on;
  otherwise they are refused as before. A `testFixtures` builder makes real trigger packages for tests.
- **Definitions:** `GET /triggers/definitions` lists the trigger exports of uploaded versions with a security delta
  against the same trigger in the version uploaded before (new export, kind or mode, more executors, permissions,
  routes, TCP destinations, secrets, tiers, `DROP_*`, `SKIP_AND_RECORD`, event type, a higher event rate).
- **Assignments:** `POST/GET/PATCH/DELETE /triggers/assignments[/{name}]` with `trigger.manage` (writes) or
  `trigger.read` (reads) on every target device and Device Group; the config validated against the signed
  `configSchema` (local `$ref` only, so a tenant schema never makes Control fetch anything); JSON Merge Patch of labels
  and spec; `If-Match` with a weak ETag (412 stale, 428 missing); `Idempotency-Key` replay; `?dryRun=true` with the
  instance changes; label selectors and cursor pages; audit of every committed mutation. A removal tombstones the
  row; the name can be reused. Bounds: 256 assignments per tenant, 4096 instances per assignment, 32 per device.
- **Instances:** one per assignment and resolved device (devices and enabled group members, minus exclusions),
  recomputed from current membership, so a device that joins a group later gets the instance on its next fetch.
  `GET /triggers/instances` filters by assignment and executor. Phases stay `Pending` until ingest (module 5).
- **Desired document:** `GET /endpoint/triggers/desired` (device certificate) returns a compact JWS, RS256, by the
  organization deployment key with `typ` `toolgate-trigger-desired+jws`; 24 h expiry; the generation advances only
  when the device's resolved set changes.
- **Client leases:** `TriggerService.leases` issues or renews the lease of every Running or Paused instance a device
  resolves to and lists revoked ones once; `leaseValid` checks executor, epoch, expiry and the token against its
  stored SHA-256 digest. Stop releases, Remove revokes, and Run after Stop or a new config revision is a new epoch.
- **Tables and permissions:** `control_triggers` in the fresh PostgreSQL `V1` schema (forced row-level security,
  tenant fence, no-delete and revision guard, capacity accounting) and its SQLite quickstart twin, in
  `schema-ownership.json`; `trigger.read` and `trigger.manage` in the permission catalog.
- **Contracts and API spec:** the admin resources in `trigger.schema.json` (ADR 058 *Amendment (triggers module 4)*),
  regenerated for Java, Rust, TypeScript and PHP with valid fixtures; the routes in the served `control-v1.yaml` (the
  desired route moved out of the proposed spec) and the console's generated operations.

**Checks run** (Windows workstation, 2026-11-07; PostgreSQL 17 in Docker via `tools/control/check.py`):

| Check | Result |
|---|---|
| `TriggerTest` on SQLite and on an isolated PostgreSQL 17 (package acceptance and security delta; assignment lifecycle, ETags, idempotency, dry run, merge patch, stop/run/remove, later group join, pagination; desired-document signing and verification; cross-tenant 404s and permission negatives) | Pass: 4 tests, each scenario on both stores |
| Control on PostgreSQL: `TriggerTest`, row security and ownership, capacity, SQLite and PostgreSQL stores, permissions, server packages, fleet, endpoint, package archive baseline, compatibility, provisioning, marketplace, SDK builder, MCP servers, core inventory, contract dependency, initialization, tenant lifecycle, domain, `adapter.*` | Pass: 189 tests in 49 suites, 4 skipped |
| `backend-libs/package-archive` (`TriggerPackageTest` with a CLI-built `inbox-watch` package for parity, `PackageArchiveTest`) | Pass: 9 tests |
| `generate.py --check`, `fixtures.py --check`, `tools/access/permissions.py --check`, `tools/tenancy/capacity_schema.py --check`, `tools/ui/generate.py --check`, `tools/quality.py`, `tools/ci/preflight.py` | Pass |
| `tests/contracts` (incl. `test_trigger_contracts.py` longest-value and bounds tests for the new resources) | 176 pass, 2 skipped; the 3 that fail on unmodified `main` on this machine fail (2 symlink, 1 scorecard) |
| `tests/ci` (`PYTHONUTF8=1`) | Pass: 403, 10 skipped |
| Rust 1.94 (Docker): `cargo fmt --all --check`, contracts crate, `toolgate-cli`, `cargo check --tests` client and Tool Host | Pass: contracts 4, CLI 43 |
| PHP 8.2 (Docker) round trips and lint; TypeScript contracts check and tests | Pass: 298 v1 and 367 v2 round trips; 2 TS tests |
| Admin UI `ui:check` and tests (generated permissions and operations) | Pass: 255 tests in 36 files |

**Not rerun for this module:** the Gradle `check` of the Java SDK modules and `contracts-java` after the contract
additions (the Java bindings compiled inside the Control build; module 2's evidence has the last full SDK run); the
repository pre-commit script.

**Not run:** the M12 scenarios (module 4 owns none); the console page (module 6); an HTTP-level test of the resource
class (the service is tested directly, as Fleet's is).

**Outstanding before the module is Done:** review and merge after modules 1 and 2.
