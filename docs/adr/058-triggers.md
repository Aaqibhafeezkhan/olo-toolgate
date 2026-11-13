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

## Amendment (triggers module 2)

Packaging trigger exports needed a manifest member the module 1 contracts did not have, so module 2 adds it in place
(fresh build, no compatibility):

- `PackageManifest.triggers` lists `TriggerDefinition {descriptor, triggerDescriptorDigest, runtimeId, entryPoint,
  artifactIds}`. `triggers/<name>.json` holds each descriptor's canonical bytes; `triggerDescriptorDigest` is their
  SHA-256, like `toolDescriptorDigest`.
- `descriptorSetDigest` covers both export kinds: the digest of one array of `{toolId, toolDescriptorDigest}` and
  `{triggerId, triggerDescriptorDigest}` entries sorted by id. Without triggers it is the tool set digest unchanged.
- Control refuses a package with `triggers` until it accepts trigger definitions (module 4), as it refuses a check-in
  with `triggers` until ingest exists.
- A retried emit (after `RETRYABLE_REJECTION`) is the same `EMIT` frame with the same `emitSeq`; an `emitSeq` never
  names a different event. A POLL check's `CHECKPOINT` is sent only once every emit of that check is resolved.

## Amendment (triggers module 4)

Control's client trigger core needed admin resources and one signature type the module 1 contracts did not have, so
module 4 adds them in place:

- `trigger.schema.json` gains the admin resources of plan 9 and 10: `TriggerAssignment` (with `TriggerAssignmentSpec`,
  `TriggerTarget`, `TriggerAssignmentMetadata`, `TriggerAssignmentStatus`), `TriggerAssignmentCreate`,
  `TriggerAssignmentResult` (with the instance changes a mutation or dry run makes), `TriggerInstanceView`,
  `TriggerDefinitionRecord` (with its security delta) and their pages. Labels are a bounded list of key/value pairs,
  not a map, so every value stays bounded.
- `FleetSignatureHeader.typ` admits `toolgate-trigger-desired+jws`. The desired trigger document is signed by the same
  organization deployment key as the fleet desired document (ADR 010) but under its own type, so neither verifies as
  the other. This settles the module 1 open item for client executors; Tool Hosts (module 8) still need the key.
- A client lease's bearer token is stored only as its SHA-256 digest; renewal keeps the epoch and rotates the token.
  A new epoch starts on first issue, after a release or revocation, after expiry and on a new config revision.

## Consequences

- Executors, Control and the SDKs share one frozen wire contract; later modules implement it without new transport.
- The worst-case size test fails the build whenever a schema change breaks a byte budget.
- Leases authorize running a trigger and reporting its events only. Actions remain online admissions in Control
  under the #230 effect path (plan section 14).
- Open item for module 4: how Tool Hosts obtain the deployment-key verification key for the desired document
  (tenant discovery or the Tool Host registration).

## Amendment (triggers module 6)

The signed fleet trigger registration includes the package archive digest.
The client resolves a trigger only when the active fleet release matches both
the package and descriptor digests named by the signed desired instance.
Recheck resolution after fleet activation changes even if the trigger desired
generation stays the same. This makes a pending upgrade wait for delivery
of its exact package and prevents a previous binary with the same descriptor
from satisfying readiness for a replacement.
