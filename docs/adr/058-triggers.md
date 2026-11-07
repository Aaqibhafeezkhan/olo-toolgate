<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 058: Triggers

**Status:** Proposed at gate D0 (issue #274, triggers module 1). The design is
[`device-triggers-plan.md`](../architecture/device-triggers-plan.md) revision 12, sections 5–10. Merging the module 1
PR records D0 approval of the wire contracts and the delivery model; the later modules implement them.

## Context

ToolGate governs tool invocation, but nothing can react to a condition by itself. Triggers let package authors write
SDK-built POLL or PUSH observers that admins deploy to Tool Host pools (server) or devices and Device Groups (client).
Executors report status and events to Control, and Control can later start authorized MCP work from them.

This ADR amends ADR 013 (lease-bound broker, tunnels and secrets), ADR 014 (trigger exports in package format v2),
ADR 027 (trigger frames and `FILES_WATCH`) and the Tool SDK authoring contract.

## Decision

1. **One contract file.** `packages/contracts/schemas/v2/trigger.schema.json` defines the trigger descriptor, the
   trigger mode of tool protocol v2 (stdio frames), the CloudEvents 1.0 envelope, the signed desired document, the
   Trigger Sync messages and the chunked checkpoint transfer. Bindings are generated for Java, Rust, TypeScript and
   PHP like every other v2 definition.
2. **One sync protocol for both executors.** `EndpointCheckIn.triggers` carries `TriggerSync` and
   `EndpointCheckInAck.triggers` carries `TriggerSyncAck`. There is no second channel. Until Control implements
   ingest (module 5), Control refuses a check-in carrying `triggers` rather than ignoring it.
3. **Bounded by construction.** Every string reachable from a trigger message declares `maxLength`, every array
   `maxItems`, and every opaque value (event data, config, checkpoint state) `x-toolgate-max-bytes`. A contract test
   builds the worst-case message for 32 client and 256 Tool Host instances and checks the byte budgets below.
4. **Byte budgets.** `TriggerSync` ≤ 192 KiB, filled in priority order: `STATE` events ≤ 31 KiB, one snapshot page
   ≤ 96 KiB, changed checkpoint metadata ≤ 16 KiB, `fired` events ≤ 48 KiB, with 1 KiB for the other members.
   `TriggerSyncAck` ≤ 128 KiB; acknowledgements come first and leases are paged. A whole check-in or ack is at most
   256 KiB, which Control advertises in `maxCheckInBytes` (operators may lower it, never raise it). What does not
   fit sets `pending` or `checkInNow`. Checkpoint bodies over 4 KiB never travel in the check-in.
5. **Sequence namespaces.** `emitSeq` (one process, stdio only), `outboxSeq` (one instance and lease epoch,
   gap-free, persisted), `leaseEpoch` (Control fencing number) and `checkpointId` (one process) are distinct and
   always named. Persisted checkpoints use `{leaseEpoch, afterOutboxSeq}`, never `emitSeq`.
6. **Normative delivery model.** `docs/specs/trigger_delivery.tla` specifies the per-event states and transitions
   T1–T15 of plan section 6.6.1. TLC checks it against the safety invariants and the replayable liveness property:
   exhaustively for 2 events, 2 epochs and 2 executors (crash and failover in separate configurations), and by
   fixed-seed simulation with every fault combined at 3 events; `tools/triggers/model_check.py` runs it. SDK, supervisor and Control implementations must match it, and the supervisor and Control
   property tests (modules 3 and 5) are generated from it.
7. **Fresh build.** Triggers are unreleased. There is no compatibility with earlier drafts, no migration and no
   legacy path (plan section 0). A contract change before release edits the schema in place and regenerates the
   bindings.

## Consequences

- Executors, Control and the SDKs share one frozen wire contract; later modules implement it without new transport.
- The worst-case size test fails the build whenever a schema change breaks a byte budget.
- Leases authorize running a trigger and reporting its events only. Actions remain online admissions in Control
  under the #230 effect path (plan section 14).
- Open item for module 4: how Tool Hosts obtain the deployment-key verification key for the desired document
  (tenant discovery or the Tool Host registration).
