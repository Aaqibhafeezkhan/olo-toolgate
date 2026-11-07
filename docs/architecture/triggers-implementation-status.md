<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Triggers implementation status

This is the live progress document for issue #274 (server and client triggers). The design is
[`device-triggers-plan.md`](device-triggers-plan.md) (revision 12). Module scope, weights, dependencies and
*Done when* criteria come from its section 24.2; nothing here changes them silently.

## Overall progress

Updated: **2026-11-07**.

**Overall: 0% complete; 100% pending.** 0 of 18 modules merged. Module 1 is implemented and verified, TLC
included, and awaits gate D0 review in its PR (see Module 1 evidence). Module 3 is implemented and verified in its
own PR, based on module 1 (see Module 3 evidence).

| Slice / gate | Needs modules | Status |
|---|---|---|
| Slice 1 — client POLL trigger end to end (35%) | 1–5 | ⏳ Pending |
| Slice 2 — server trigger with failover (56%) | 1–8 | ⏳ Pending |
| Slice 3 — schedule and device trigger → authorized MCP (85%) | 1–14 | ⏳ Pending |
| Slice 4 — composite MCP from an agent and an action (92%) | 1–15 | ⏳ Pending |
| Release-ready (99%) | 1–17 | ⏳ Pending |
| Gate G-STATE (TLC model, stateful property tests, deterministic simulation) | 1, 3, 17 | 🔵 Item 1 (TLC) passes at the bounds in *Baseline changes*. Item 2 is delivered for the supervisor crate in the module 3 PR (the jqwik stateful tests for Control ingest belong to module 5). Item 3 pending |
| Gate G-IFC (conformance and adversarial corpus, security sign-off) | 11, 16 | ⏳ Not run |
| M12 end-to-end scenarios | all | 0 of 58 passing |

## Module progress

| # | Module | Track | Weight | Status | Earned | PR |
|---|---|---|---:|---|---:|---|
| 1 | Contracts, ADR and model | A | 5% | 🔵 In progress | 0% | — |
| 2 | Java SDK, POLL | B | 6% | ⏳ Pending | 0% | — |
| 3 | Supervisor crate | C | 8% | 🔵 In this PR | 0% | — |
| 4 | Control core for client triggers | D | 8% | ⏳ Pending | 0% | — |
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

The baseline is plan revision 12, section 24.2. Module weights and scope are unchanged.

| Date | Change | Old | New | Reason |
|---|---|---|---|---|
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

### Module 3 — Supervisor crate (in this PR)

Scope: `crates/trigger-supervisor`: process lifecycle, the trigger-mode stdio frames, the outbox with `outboxSeq`,
the `LOCAL` barrier, rejection classes, loss records, backoff and crash loop, health, the sequence namespaces.

Done when: stateful property tests against the model (G-STATE item 2) with a fake runtime and a fake Control;
crash-point tests T1–T5 and T10.

**Delivered** (branch `feature/triggers-m03-supervisor`, based on module 1 at `1af150e3`, PR base
`feature/triggers-m01-contracts` until #281 merges):

- New workspace member `crates/trigger-supervisor` ([README](../../crates/trigger-supervisor/README.md)), not yet
  linked into `apps/*` (modules 5 and 8). Every stdio frame and wire value is a generated contract type.
- `store`: the outbox under `(leaseEpoch, outboxSeq)` with no separate sequence counter (T1), a truncation epoch mark
  that keeps the high `outboxSeq` (S1), `LOCAL` and `ACCEPTED` checkpoints `{leaseEpoch, afterOutboxSeq, state}` and
  the halt condition. `FsStore` fsyncs each append, rolls back a failed one, writes documents through temp file,
  fsync and rename, drops a torn tail on recovery, and refuses another format or instance without converting it.
- `supervisor`: a deterministic state machine for one instance. Admission with the three outcome classes (schema
  and size are terminal; rate limit, local storage, backpressure timeout, stopping and lease loss are retryable),
  loss records under `SKIP_AND_RECORD`, a persisted halt under `HALT`, the contiguous `LOCAL` barrier over
  out-of-order completions, `TriggerSyncAck` handling (truncate, then `ACCEPTED_UPTO` and the `ACCEPTED` checkpoint;
  `dispositions`, `retryLater`, `revoked`, `rejected`), lease epochs, health (start timeout, three missed
  heartbeats, five minutes `UNHEALTHY`), stop grace with SIGTERM and SIGKILL, the resync timer, T12 migration or
  discard, T13 removal with an undrained count, and T14 resume from Control's `ACCEPTED` checkpoint.
- `restart`: full-jitter backoff and the 5-in-10-minutes crash loop; `process`: the tokio process with the
  trigger environment and bounded framing.
- `emitSeq` exists only in process memory and stdio frames; tests assert it never appears in a persisted value or
  in a `TriggerSync` record.

**Contract change (in place, bindings regenerated):** `TriggerRejection` in `TriggerSyncAck.rejected` gained a
required `leaseEpoch`. Without it an executor cannot tell which epoch's records to drop, and dropping all of them
prunes unacknowledged records of the current epoch when Control fences only an older one (P2). Plan 8.3 is updated
to match.

**Model findings while implementing:**

- The `ACCEPTED` level of a process must not start until the process's start checkpoint is itself accepted;
  otherwise records left by an earlier process in the outbox can be passed. The crate enforces it; removing the
  check makes the property test fail (below).
- When Control fences an epoch whose records were not all accepted (the same-executor drain window expiring), the
  `LOCAL` checkpoint may already rely on them. The supervisor now stops the process without committing further and
  falls back to its `ACCEPTED` checkpoint before pruning (L). The model has no drain expiry; the property test
  models it, and removing the fallback makes it fail with an L violation.

**Checks run** (Windows workstation, 2026-11-07; Rust in Docker `rust:1.94-bookworm`, rustc 1.94.1,
image `sha256:6ae102bd…`):

| Check | Result |
|---|---|
| `cargo fmt --all --check`, `cargo clippy --workspace --all-targets --locked -- -D warnings` | Pass |
| `cargo test --workspace --locked --no-fail-fast` | Pass: 366 passed, 16 ignored, in 52 test binaries. A first run without `--no-fail-fast` failed one endpoint-client test (`power_off_reports_once_and_power_on_resumes_without_reenrollment`, unchanged code); it passed three times alone and in the full rerun |
| Supervisor crate tests | Pass: 7 unit, 19 crash-point (`tests/crash_points.rs`), 15 lifecycle, 3 process (Unix), and the model test |
| G-STATE item 2: `tests/model.rs` (`proptest-state-machine`, up to 120 steps per case, two executors, 4 items, 4 epochs) | Pass at 512 cases (default), 20,000 cases (release build, 23 s) and 200,000 cases (release build, 214 s) |
| Injected-defect check (10 deliberate bugs in `supervisor.rs`, one at a time, on the final code) | Every bug fails the crate tests. The model test alone caught 9 of 10 in a 2,000-case run per bug: the barrier passing a retryable emit, a non-contiguous LOCAL commit, `ACCEPTED_UPTO` without an ack, truncating one record too many, reusing an `outboxSeq`, ACCEPTED before the start checkpoint is accepted, HALT resolving the barrier, a fence pruning every epoch, no fallback on a fence. The tenth, a resyncing process that still commits, passed that run; the model test caught it in another run, and the T10 fence test now catches it every time |
| T1–T5, T10 crash points (`tests/crash_points.rs`) | Pass: crash before and after each append, loss-record append, halt write, checkpoint write and truncation; clean storage failures; out-of-order completion; overflow without pruning; truncation before `ACCEPTED_UPTO`; one-epoch and compromised fencing; the plan 6.6 review fixture; `FsStore` torn append, interrupted rename and refused stores |
| `tools/contracts/generate.py --check`, `fixtures.py --check`, `version.py --check`, `tools/quality.py`, `tools/ci/preflight.py` | Pass |
| `tools/ci/pr_policy.py --staged`, `tools/ci/secret-scan.mjs` | Pass |
| `tests/ci` (413 tests, `PYTHONUTF8=1`) | Pass, 10 skipped (includes `test_rust_docker_workspace`: every Dockerfile copies the new member) |
| `tests/contracts` (181 tests) | 176 pass, 2 skipped. The same 3 environment failures as on `main` (two symlink tests, WinError 1314; one scorecard observation) |
| Contract bindings after the `TriggerRejection` change | Pass: PHP 8.2 (Docker) 298 v1 and 348 v2 round trips and 646 files linted; TypeScript contracts check, build and tests; Java `:contracts-java:test` (4 tests) |

**Not run:** the M12 scenarios (module 3 owns none); a Windows build of the crate (no local Rust toolchain, and CI's Windows
jobs build only the client, CLI and Tool Host packages, which link the crate from modules 5 and 8); the jqwik stateful tests for Control ingest (module 5).

**Known limits:** `DROP_OLDEST` behaves as `DROP_NEWEST` because a durable record is never pruned (P2); POLL
backpressure blocks like `BLOCK` until the SDK scheduler reads `Degraded (Backpressure)`; clearing a Control-side
halt after a fix is left to modules 5 and 6. The supervisor trusts its executor for lease tokens and the
renew deadline (modules 5 and 8 verify them).
