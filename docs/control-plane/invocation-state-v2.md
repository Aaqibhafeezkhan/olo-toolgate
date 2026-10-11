# Invocation state v2: tasks, ledger, business keys

## Purpose

This is the reviewed state-machine specification behind design gate D2 (plan.md §12.7, §12.8). It says how the v2 task, execution-ledger, lease and business-key state described in plan.md §11.3, §11.7 and §12 lives in Control's existing invocation storage. It also fixes the exact migrations that add it.

It is normative. Milestone code implements it. A change after D2 passes needs an amendment to ADR 016.

The schema shapes are in `packages/contracts/schemas/v2/invocation.schema.json` and `permit.schema.json`. The migrations are the files in [migrations-v2/](migrations-v2/). [`tests/contracts/test_migration_specs_v2.py`](../../tests/contracts/test_migration_specs_v2.py) applies them to both database trees and runs the same scenarios on each.

## 1. Authority

- **One writer.** Control's database is the only state database, and only Control writes to it (plan.md §12.8). The Gateway and Tool Host change state only through the Control endpoints in §3. They hold no database role.
- **One row per governed invocation.** Every governed invocation already has a `control_enterprise_invocations` row, created by `POST /access/invocations`, and v2 keeps that. Plan.md §12.1 decides only whether that row also gets **task columns**, a **business-key row** and a **result row**. "No task record" for a plain short call means none of those three, not "no invocation row".
- **The v1 state stays authoritative.** The `state` column and its `EnterpriseInvocationState` values do not change. The v2 `task_state` column is a projection of `state` plus three sub-states (`INPUT_REQUIRED`, `CANCEL_REQUESTED`, `REJECTED`) that `state` cannot express. A database constraint enforces the projection (§2), so the two can never disagree.
- **Same transaction discipline.** Every write in this document runs inside Control's existing per-tenant transaction: `pg_advisory_xact_lock`, expected revisions, audit and idempotency, all in one commit. SQLite uses `BEGIN IMMEDIATE` with WAL and `synchronous=FULL`.

## 2. State mapping

| Task state (`TaskState`) | Control `state` | MCP `status` | Notes |
|---|---|---|---|
| `ACCEPTED` | none | — | Gateway spool only (plan.md §12.7 row 1). A row is created directly in the next state, and the first recorded transition is `ACCEPTED → …`. |
| `AWAITING_APPROVAL` | `PENDING_APPROVAL` | `input_required` | |
| `QUEUED` | `QUEUED` | `working` | |
| `DISPATCHED` | `RESERVED` or `DISPATCHED` | `working` | `RESERVED`: permit minted, nonce reserved. `DISPATCHED`: client relay delivered. |
| `RUNNING` | `EXECUTING` | `working` | Permit consumed; the nonce is the fencing token. |
| `INPUT_REQUIRED` | `EXECUTING` | `input_required` | The tool asked for input. The fencing token stays valid. |
| `CANCEL_REQUESTED` | `RESERVED`, `DISPATCHED` or `EXECUTING` | `working` | Needs `cancel_requested_at`. |
| `COMPLETED` | `SUCCEEDED` | `completed` | |
| `FAILED` | `FAILED` or `PARTIAL` | `failed` | `PARTIAL` (a batch reconciled as partly applied) reports code `PARTIAL_OUTCOME`, `retryable: false`, with the completed resources as evidence. |
| `REJECTED` | `CANCELLED` | `failed` | Approval denied or revoked. **Change from today:** a denial now ends the invocation in the same transaction, instead of leaving it `PENDING_APPROVAL` until it expires. |
| `EXPIRED` | `EXPIRED` | `failed` | |
| `CANCELLED` | `CANCELLED` | `cancelled` | |
| `OUTCOME_UNKNOWN` | `OUTCOME_UNKNOWN` | `failed` | Code `OUTCOME_UNKNOWN`, `retryable: false`. |

Constraint `control_invocation_task_projection` (Flyway) and the `control_invocation_v2_*` triggers (Quickstart) reject any other pair.

## 3. Transitions

Each transition is one Control transaction. It checks the expected revision, the current authorization epoch, the kill set ([kill-switch.md](kill-switch.md)) and the conditions listed, then writes everything in the "Writes" column, appends the `TaskTransition` to `task_document.transitions` (capped at 64, oldest dropped after the first), and writes audit. The endpoint paths are relative to `/api/control/v1`. The proposed additions are in `packages/contracts/openapi/proposed/control-v1.proposed.yaml`.

| # | From → to (`state` / `task_state`) | Caller and endpoint | Conditions | Writes |
|---|---|---|---|---|
| T1 | none → `PENDING_APPROVAL` / `AWAITING_APPROVAL`, or → `QUEUED` / `QUEUED` | Gateway, `POST access/invocations` with an `InvocationSubmission` body; returns `InvocationAdmission` | Policy decision as today. Business key handled first (§4). | Invocation row with v2 columns. Task columns when plan.md §12.1 requires a task. Business-key row when a key or dedupe window applies. Approval row for ASK. Budgets as today. |
| T2 | `PENDING_APPROVAL` → `QUEUED` | Approver, existing approval decision | All obligations approved | Approval `APPROVED`; invocation `QUEUED`. One transaction (plan.md §12.7 row 3). |
| T3 | `PENDING_APPROVAL` → `CANCELLED` / `REJECTED` | Approver, existing approval decision | Deny or revoke | Approval `DENIED`/`REVOKED`; invocation `CANCELLED`; business-key `outcome = REJECTED`. |
| T4 | `QUEUED` → `RESERVED` / `DISPATCHED` | Gateway, `POST access/invocations/reserve` with a `PermitReservationRequest` body | Fresh authority; approval consumed once (plan.md §12.7 row 4) | Nonce row (`control_enterprise_nonces`); permit signed (ADR 013). Unchanged from today apart from the v2 claims. |
| T5 | `RESERVED` → `EXECUTING` / `RUNNING` | Tool Host or device, `POST access/permits/consume` | Permit valid; `aud` is the caller's workload identity; nonce unconsumed and unexpired; digests match the row | Nonce `consumed_at`; `lease_holder`, `lease_expires_at = now + lease`. Returns `PermitConsumed {fencingToken = nonce, leaseExpiresAtUnixMs}`. |
| T6 | `EXECUTING` → `EXECUTING` (lease renewal) | Tool Host, `POST access/invocations/{id}/heartbeat` | `state = EXECUTING`; fencing token equals the consumed nonce; caller equals `lease_holder`; no `TERMINATE` kill covers it. After T8 it still answers, with `cancelRequested = true`, but no longer extends the lease. | `lease_expires_at = min(now + lease, deadline + 5 s)`; latest `progress` into `task_document`. Returns `LeaseRenewal`, which carries `cancelRequested` and any waiting `inputResponses`. |
| T7 | `EXECUTING` / `RUNNING` ⇄ `INPUT_REQUIRED` | Tool Host sends `inputRequest` on a heartbeat (enter); Gateway sends `POST access/tasks/{taskId}/input` (leave) | Task exists; owner digest matches (§7) | `task_input_deadline_at` set on entry and cleared on exit. The input is stored in `task_document` until the next heartbeat returns it in `LeaseRenewal.inputResponses`. |
| T8 | any non-terminal → `CANCEL_REQUESTED` | Gateway (`tasks/cancel`), admin, kill `TERMINATE` | Task exists, or the invocation is `RESERVED`/`DISPATCHED`/`EXECUTING` | `cancel_requested_at`; the fencing token is invalidated for secret delivery and broker checks, and the next T6 returns `cancelRequested = true`. `QUEUED` and `PENDING_APPROVAL` go straight to `CANCELLED` instead. |
| T9 | `EXECUTING` → terminal | Tool Host or device, `POST access/effects/report` keyed by `(invocationId, fencingToken)` | Fencing token valid. Stale token: the report is recorded as evidence only (§6). | Terminal `state`/`task_state`; result row (§6); business-key `outcome`; outbox row. |
| T10 | `EXECUTING` → `FAILED` with `not_started = true` | Tool Host, `effects/report` with `outcome = FAILED` and `notStarted` set to the refusal reason | Fencing token valid; the host's spool shows no sandbox start (plan.md §12.7 row 6, `ABANDONED_BEFORE_START`) | As T9. The business key may be rebound (§4.4). |
| T11 | `EXECUTING` → `OUTCOME_UNKNOWN` (ledger) or → `QUEUED` (non-ledger, attempt < 3) or → `FAILED` | Control expiry job | `lease_expires_at <= now` or `expires_at <= now` | Ledger tools: `OUTCOME_UNKNOWN`. Non-ledger tools with a task: back to `QUEUED` with a new attempt, otherwise `FAILED`. Lease columns kept as evidence. |
| T12 | `PENDING_APPROVAL`, `QUEUED` or `INPUT_REQUIRED` → `EXPIRED` | Control expiry job | `task_input_deadline_at <= now`, pickup timeout, or `expires_at <= now` before consumption | Nonce, if any, left unconsumed |
| T13 | `OUTCOME_UNKNOWN` → `SUCCEEDED`, `FAILED` or `PARTIAL` | Late T9 report with the old fencing token, or the existing reconciliation endpoint | Evidence attached | Business-key `outcome` moves from `OUTCOME_UNKNOWN` once. Never the other way (plan.md §12.7 row 8). |
| T14 | terminal → terminal (purge) | Control retention job | `retain_until <= now` | Result payload replaced by its tombstone (§6); business-key row tombstoned (§4.5). `state` does not change. |

A transition that is not in this table is refused with `409`.

**Retries of a Control call.** Each caller endpoint takes an `Idempotency-Key` and uses Control's existing idempotency table, so a lost response is replayed rather than applied twice. If a commit's outcome is unknown, the caller re-reads by `invocationId`, which the Gateway generated (plan.md §12.7 row 2).

## 4. Business-key reservations

Table `control_business_keys`, one row per `(tenant, tool, namespace, namespace identity, key hash)`. It is separate from the invocation row because its tombstone outlives the result it replaced. Plan.md §12.8 had placed a unique index on the invocation table; this spec supersedes that.

### 4.1 Inputs

- **`namespace_identity`** comes from the authenticated request, never from arguments:
  - `AGENT`: the calling `agentId`;
  - `DELEGATED_USER`: the verified `userId`. A SERVICE-mode call is refused with `BUSINESS_KEY_NAMESPACE_MISMATCH` before any row is read;
  - `TENANT`: the empty string. Constraints enforce both directions.
- **`business_key_hash`** is the lowercase hex SHA-256 of `"toolgate-business-key-v1" || 0x00 || tenantId || 0x00 || key`, with the key as UTF-8. The Gateway computes it, and Control never sees the raw key. It is not secret: anyone with database read access can test guesses. The values forwarded upstream use the keyed hashes of plan.md §12.3 instead.
- **`fingerprint`** is `sha256:` over the JCS of `{argumentsDigest, packageMajor, effectSafety, catalogScopeKind, userId?}`. `userId` is included for `AGENT` and `TENANT` only (plan.md §11.7).
- **Dedupe window** (`ALLOW_WITH_DEDUPE` with no key): `source = DEDUPE_WINDOW`, the key hash is the SHA-256 of `toolId || 0x00 || fingerprint`, and `dedupe_expires_at = now + dedupeWindowMs`.

### 4.2 The reservation transaction

T1 runs these steps under the tenant lock, in one transaction:

1. `SELECT … FOR UPDATE` the row for the primary key. Under the tenant lock this is the whole race; the primary key remains the backstop if a future path skips the lock.
2. **No row:** insert the business-key row, the invocation row, its task columns and (for ledger tools) the reservation, all together. The result is `NEW`.
3. **Row exists:** apply §4.3.
4. A unique violation at commit is treated as "row exists": the transaction rolls back and is retried once, and the retry takes step 3.

### 4.3 Outcomes for an existing row

Evaluated in this order:

| Situation | Result | Returned |
|---|---|---|
| Row tombstoned | `EXPIRED_KEY` | Policy then rejects (default) or sends the call to ASK. |
| Different caller identity (another agent or user in `TENANT`; the same agent acting for a different user in `AGENT`) | `IDEMPOTENCY_KEY_IN_USE` | Nothing else. Same body and same code path whether or not the fingerprint matches. Audit records both callers. `TENANT` with `shareResultsWithinTenant` instead continues with the fingerprint rows below. |
| `DELEGATED_USER`, same user, different agent | as the same caller | Only if the second agent is authorised for the tool now; the result goes through its own catalog scope check. |
| Same caller, fingerprint differs | `IDEMPOTENCY_CONFLICT` | No result |
| Same caller, fingerprint matches, rebind allowed (§4.4) | `NEW` | The row is rebound to the new invocation, and audit links the two. |
| Same caller, fingerprint matches | `EXISTING` | `invocationId` and task state; the result once terminal. Never executed again. |
| `DEDUPE_WINDOW` row with `dedupe_expires_at <= now` | `NEW` | The row is replaced whole (conditional upsert). |

### 4.4 Rebinding after work that provably never ran

A matched `EXISTING` row may be rebound to a new invocation only when the previous invocation ended `EXPIRED` before its nonce was consumed, or `FAILED` with `not_started = true` (T10). In both cases Control has proof that no effect could have started. A `REJECTED` (denied) invocation is **not** rebound: a human said no, and the same key keeps returning that answer.

The trigger `control_business_key_guard` allows a rebind only from outcome `EXPIRED` or `FAILED`. Control's Java code additionally checks the two conditions above. Owner and fingerprint never change.

### 4.5 Outcome, retention and tombstones

- `outcome` is set by the terminal transition (T3, T9, T10, T11, T12) in the same transaction. It is final, except `OUTCOME_UNKNOWN → COMPLETED | FAILED` (T13).
- `retain_until = created_at + businessKeyRetention` for the namespace. The default is 30 days for `requiresBusinessKey` tools, and never shorter than the upstream's declared idempotency window.
- T14 sets `tombstoned_at` after `retain_until`. The row, minus the result, then stays for at least a year (tenant policy may lengthen it) and backs `EXPIRED_KEY`. Rows are never deleted by the runtime role, which has no `DELETE` grant.

## 5. Execution ledger, fencing and leases

- **The ledger is the invocation row.** For `requires_execution_ledger = true` (`NON_IDEMPOTENT` or `UNKNOWN`), T5 is the only way into `EXECUTING`, and it happens once per invocation: the nonce is single-use and the row is never reclaimed (plan.md §11.3). Non-ledger tools use the same T5, but T11 may send them back to `QUEUED` for a new attempt with a new nonce.
- **Fencing token** is the consumed nonce. Secret delivery, broker capabilities, heartbeats and result reports all present it.
  - Secret delivery and broker checks reject it once `cancel_requested_at` is set.
  - Heartbeats and result reports still accept it while the task is `CANCEL_REQUESTED`. A result that arrives then ends the task `COMPLETED` or `FAILED`, with the race recorded (plan.md §12.2). Once the invocation is `CANCELLED`, because the host confirmed the cancellation or the lease ran out, a later result is evidence only (plan.md §12.7 row 8).
  - A `TERMINATE` kill that covers the invocation invalidates the token everywhere at once.
- **Lease** defaults to 30 s, configurable per pool from 10 s to 120 s. The Tool Host renews every lease/3 (T6). The lease never extends past the permit's `deadlineUnixMs` plus 5 s for the result report. If renewal fails, the host keeps running until the lease ends, then terminates the sandbox (plan.md §12.7 row 7). Control's expiry job applies T11.
- **Epoch.** T5, T6, T9, secret delivery and every broker capability check compare the tenant's current `authorization_epoch` with the one the permit carries, re-evaluating only when it moved. A kill bumps the epoch ([kill-switch.md](kill-switch.md) §3).

## 6. Results

- **Where:** `control_invocation_results`, one row per invocation, holding the exact `ResultMessage` bytes (UTF-8 JSON) up to the 4 MiB output ceiling. All v2 task results are stored here, including small ones, so there is a single read path. The relay queue's 64 KiB cap still applies to client-tool relay frames, unchanged.
- **Idempotent writes:** the Tool Host's durable outbox sends `effects/report` keyed by `(invocationId, fencingToken)`. A repeat with the same token and the same `result_digest` is a no-op `200`. A repeat with a different digest is `409` and audited.
- **Stale token:** a report whose fencing token is no longer valid (cancelled, killed, or a newer attempt) is written to audit as evidence and changes nothing, except T13.
- **Immutability:** the row is written once. The only update the trigger allows is the purge (T14), which sets `payload = NULL` and `purged_at` and keeps `result_digest` and `byte_length` as the tombstone. The runtime role has no `DELETE` grant, and the trigger refuses deletes as well.
- **Retention:** `retain_until` is the longer of the task TTL and `businessKeyRetention` (plan.md §12.7 row 9).

## 7. Task ownership and the task endpoints

- **`task_owner_digest`** is the lowercase hex SHA-256 of the JCS of `{tenantId, issuer, subject?, agentId, userId?, catalogScopeKind, agentGroupId?}`, computed by the Gateway from the authenticated request. It excludes the credential hash, so a rotated token for the same identity keeps access (plan.md §12.6).
- **`GET access/tasks/{taskId}`** and **`POST access/tasks/{taskId}/input`** (proposed, added at D2) take the digest in the `ToolGate-Task-Owner` header. A missing task and a digest mismatch return the same `404`, by the same code path. They return `TaskView {invocationId, task, result?}`. `tasks/cancel` uses the existing `POST access/invocations/{id}/cancel` with the `invocationId` from `TaskView`.
- **TTL and polling:** each successful `GET` sets `task_last_polled_at` and extends `task_retain_until` by up to `pollIntervalMs × 10`, never past the tenant's hard maximum. `cancelIfAbandoned` tasks with no poll within `3 × pollIntervalMs` take T8.
- **`taskId`** is 128 random bits or more, base64url without padding (22 to 64 characters), unique per tenant (`control_invocation_task`).

## 8. Migrations

| Tree | File (specification) | Ships as | Content |
|---|---|---|---|
| Flyway (PostgreSQL) | [V18__invocation_tasks_v2.sql](migrations-v2/flyway/V18__invocation_tasks_v2.sql) | `db/migration/V18__invocation_tasks_v2.sql`, milestone M1 | §2–§7 columns, constraints and indexes; `control_business_keys`; `control_invocation_results`; guard triggers; grants |
| Quickstart (SQLite) | [V11.sql](migrations-v2/quickstart/V11.sql) | `db/quickstart/V11.sql`, M1, with the `SqliteState` cap raised from 10 to 11 | Same state. Cross-column checks are triggers, because SQLite can't add table constraints. |
| Flyway | [V19__kill_switch.sql](migrations-v2/flyway/V19__kill_switch.sql) | `db/migration/V19__kill_switch.sql`, with the kill switch | [kill-switch.md](kill-switch.md) §2 |
| Quickstart | [V12.sql](migrations-v2/quickstart/V12.sql) | `db/quickstart/V12.sql`, cap raised to 12 | Same |

**Rules:**
- **Frozen content.** The files ship byte-for-byte; the Quickstart runner checksums each file. Only the version number may change, if another migration lands first, and then both trees and this table move together.
- **Additive only.** Every new column is nullable or has a default, and no existing column, constraint or index changes. v1 rows and v1 code paths keep working, and an older image refuses the newer database through the runner cap (Quickstart) or Flyway's validation (PostgreSQL). There is no down-migration, as with every existing migration.
- **Verified.** `tests/contracts/test_migration_specs_v2.py` applies the current trees plus these files, on SQLite always and on PostgreSQL when `TOOLGATE_D2_POSTGRES=host:port` is set, then runs the same accept/refuse scenarios on both. It was run against PostgreSQL 16 and SQLite 3.45 for this gate.

## 9. What this does not cover

- Gateway and Tool Host spools, and their full-disk behaviour: plan.md §12.7 row 11 and [the Tool Host threat model](../security/threat-models/tool-host.md).
- Kill events: [kill-switch.md](kill-switch.md).
- Permit format and keys: ADR 013.
