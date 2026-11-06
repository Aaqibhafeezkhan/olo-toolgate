<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Operation and permit-attempt journal

Control V3 creates a durable operation with every admitted invocation. Each
reserved execution permit gets its own immutable attempt identity, using its nonce as the
attempt ID and fencing token. Invocation state transitions append snapshots to
that attempt; they never overwrite a previous attempt's observations. Existing
raw executor reports remain immutable in `control_execution_evidence`, joined by
invocation/operation ID and fencing nonce. Duplicate exact reports remain one row.
The existing nonce table distinguishes EXECUTION worker permits from SERVICE
capabilities. Service capabilities retain their original single-use checks and do
not add worker attempts. Historical LEGACY nonces are conservatively retained as
potential attempts because their original purpose cannot be proved.

The operation ID is the existing invocation ID, including business-key `op-...`
IDs. Existing APIs, device reports and permits retain their identifiers. The
internal store exposes `operation(id)` and `attempts(id, now)` within the existing
tenant transaction. Public history APIs and UI presentation are later work.

## Retry behavior

An expired **unconsumed reservation** may receive a new permit only while the
original invocation is still authorized and inside its original deadline. Control
must have the old nonce and its attempt binding, with no consumption and with its
expiry reached. Every predecessor remains in the journal. A live, consumed,
missing or unbound predecessor cannot establish that proof. Database guards
independently enforce it. A permit cannot have its identity or consumption reset.

`OUTCOME_UNKNOWN` is never automatically requeued, even for an unkeyed legacy
invocation declaring `DOWNSTREAM_IDEMPOTENCY`. A declaration alone does not prove
request binding, target deduplication or recovery semantics. Use the existing
reviewed reconciliation workflow for ambiguous work. Target-deduplicated retries
must wait for the phase-3 effect protocol. Whole-invocation deadline rebinding is
also not enabled by this increment.

## Interpretation

`control_operations.state` is a conservative summary:

| State | Meaning |
|---|---|
| RUNNING | Admission or an execution attempt is active; no final outcome is asserted. |
| OUTCOME_UNKNOWN | Consumed/ambiguous terminal work needs effect-level evidence. Historical terminal operations are also held here. |
| FAILED | A new journaled operation ended without ever consuming any permit. No worker was granted execution authority. |

An executor-reported SUCCEEDED or FAILED attempt is an observation, retained in
its own snapshot. Consumed terminal attempts remain OUTCOME_UNKNOWN at the
operation level until manifests and target/effect evidence can establish finality.
The legacy invocation and business-key outcome fields keep their existing
executor/reconciler semantics; they are **not** an effect-verified operation
receipt. This increment does not wire the new summary into caller presentation or
claim full operation finalization.

For an unconsumed reservation, `attempts(id, now)` derives EXPIRED from its immutable
permit deadline. The original RESERVED snapshot stays unchanged. A late report
increases that attempt's evidence count without changing execution authority or
replacing the reviewed invocation outcome.

Operators with existing tenant-scoped database access can inspect the records.
On PostgreSQL, set the tenant only inside the transaction (use the actual tenant
UUID and a role already permitted to access it):

```sql
BEGIN;
SET LOCAL app.tenant_id = '10000000-0000-4000-8000-000000000001';
SELECT operation_id, state, legacy, revision FROM control_operations;
SELECT operation_id, attempt_id, issued_at, expires_at FROM control_operation_attempts;
SELECT invocation_id, nonce, purpose, consumed_at FROM control_enterprise_nonces;
SELECT operation_id, attempt_id, revision, state FROM control_attempt_snapshots
ORDER BY operation_id, attempt_id, revision;
ROLLBACK;
```

Do not treat an old RESERVED snapshot as a live permit: check consumption in
`control_enterprise_nonces` and its deadline. Do not log raw report documents or
fencing identifiers outside authorized diagnostics.

## Upgrade

V3 ships for PostgreSQL/Flyway and SQLite Quickstart. V1/V2 stay byte-identical.
The migration records every retained nonce belonging to an invocation. Earlier consumed predecessors become
unknown observations; they never inherit the latest attempt's result. Historical
operations are marked `legacy`, because complete historical snapshots cannot be
reconstructed. Such terminal operations do not get automatic final success or
failure. Admission without an issued permit has an operation and no execution
attempt yet.

Upgrade Control replicas together. After V3, old code cannot issue an unjournaled
permit: the database requires the operation record and attempt binding. Missing
journal records fail closed; Control does not reconstruct proof from absence.
SQLite refuses opening V3 state with older images. Take the normal database
backup before upgrading; downgrade requires restoring that backup, rather than
editing migration checksums or deleting history. PostgreSQL backfill temporarily
removes FORCE RLS from its source tables inside the migration transaction, then
restores it before committing; all three new tables enforce forced tenant RLS.

The still-prospective kill-switch specification is renamed V4 without changing its
contents, so it remains after the newly shipped V3. See
[ADR 035](../adr/035-operation-attempt-journal.md) and the
[live progress document](../security/zero-trust-execution-status.md).
