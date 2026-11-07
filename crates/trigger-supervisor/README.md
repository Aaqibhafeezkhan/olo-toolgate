<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Trigger supervisor

The supervisor of trigger processes (issue #274, [ADR 058](../../docs/adr/058-triggers.md), plan
[section 6](../../docs/architecture/device-triggers-plan.md)). The endpoint client (module 5) and the Tool Host
(module 8) link it; it is not wired into `apps/*` yet.

| Module | What it does |
|---|---|
| `frames` | Trigger-mode stdio frames over the generated `olo-toolgate-contracts` types; no hand-written wire types |
| `store` | The durable outbox under `(leaseEpoch, outboxSeq)`, the `LOCAL` and `ACCEPTED` checkpoints and the halt condition. `FsStore` appends and fsyncs per record and writes documents through temp file, fsync and rename |
| `supervisor` | One instance's state machine: admission and rejection classes, loss records, the contiguous checkpoint barrier, Control acknowledgements, fencing, leases, health, restarts, migration and removal |
| `restart` | Backoff with full jitter and crash-loop detection (plan 6.9) |
| `process` | Spawning the trigger process with the trigger environment, bounded line framing, SIGTERM and SIGKILL |
| `memory` (feature `testing`) | An in-memory store that injects a crash before or after, or a clean failure of, any persistence point |

The supervisor has no I/O besides its store. An executor feeds it frames (`on_frame`), process exits (`on_exit`),
leases (`grant_lease`), Control acknowledgements (`on_sync_ack`) and clock ticks (`tick`); it calls `pump` after
frames to run the outbox writer, `sync_events` for each check-in, and carries out the returned `Action`s.

## Model correspondence

`docs/specs/trigger_delivery.tla` is normative. The executor-side transitions map as follows; Control-side
transitions (T6, T7, T9, T11, T15) are exercised by the fake Control in the tests and implemented by module 5.

| Model | Crate |
|---|---|
| `Start` | `tick` spawns while the instance should run, holds an unexpired lease, is not halted and its checkpoints use the package's checkpoint schema (M) |
| `Emit` | an admitted `EMIT` frame (queued, not durable) |
| `Durable`, `LossRecord` (T1, T3) | `pump` / `persist`: one append and fsync; the next `outboxSeq` is the last durable one + 1 (S1) |
| T2 | `RETRYABLE_REJECTION` (`RateLimited`, `LocalStorageError`, `BackpressureTimeout`, `Stopping`, `LeaseExpired`); nothing is persisted and the emit holds the barrier |
| `Halt` (T4) | a terminal rejection under `HALT` persists the halt condition and appends nothing |
| `LocalCheckpoint` (T5) | the newest position at or below the contiguous local watermark, written atomically |
| `ReceiveAck` (T8, T10) | `on_sync_ack`: truncate and fsync, then `ACCEPTED_UPTO` and the `ACCEPTED` checkpoint; fenced epochs are pruned unless compromised |
| `Crash` | drop the supervisor and `recover` from the store |
| `LeaseLost` | lease expiry, revocation or a newer epoch stops the process |
| `Failover`, `Reinstall` (T14) | `resume_from_control` with Control's `ACCEPTED` checkpoint (I) |
| `Upgrade` (T12) | `migrate_checkpoints` or `discard_checkpoints` |
| `Remove` (T13) | `begin_removal`, then `finish_removal` reports what was not drained (X) |

The `ACCEPTED` level of a process starts only once its start checkpoint is itself accepted, so records left by an
earlier process can never be passed. When Control fences an epoch whose records were not all accepted, the
supervisor stops the process and falls back to its `ACCEPTED` checkpoint before it prunes them (L).

## Tests

- `tests/model.rs`: G-STATE item 2, a `proptest-state-machine` test with a fake SDK runtime and a fake Control;
  the invariants are asserted after every step and liveness at the end. `TRIGGER_MODEL_CASES` sets the case count
  (512 by default).
- `tests/crash_points.rs`: T1–T5 and T10 at every crash point, the plan 6.6 review fixture, and the file store
  against torn appends, interrupted renames and unsupported stores.
- `tests/lifecycle.rs`: health, stop grace, crash loop, leases, limits, drop policy, `retryLater`, T12, T13 and
  the instance report.
- `tests/process.rs` (Unix): the process wrapper against a shell script.

## Known limits

- `DROP_OLDEST` cannot drop a durable record (P2 forbids pruning it), so it drops the event that cannot be
  admitted, like `DROP_NEWEST`.
- POLL backpressure blocks emits like `BLOCK`; the plan's "skip checks" needs the SDK's scheduler to see the
  `Degraded (Backpressure)` condition (module 5 or 7).
- Clearing a Control-side halt (a disposition under `HALT`) after a fix is left to modules 5 and 6.
