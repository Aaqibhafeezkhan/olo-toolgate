<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 016: Durable tasks, effect safety, business keys and persistence

**Status:** Accepted (design gate D2), on merge of the PR that adds this file. Amends the D1 contract set as listed in decision 8.

## Context

v2 adds long-running tools, MCP Tasks, at-most-once execution for non-idempotent tools, and business idempotency keys (plan.md §11.3, §11.7, §12). All of it needs durable state, and it must keep Control's guarantees: one writer, a per-tenant lock, Java invariant checks, and no database access for the Gateway or Tool Host (ADR [003](003-control-plane-transactions.md), ADR [006](006-ask-approvals.md)).

Control already stores every governed invocation in `control_enterprise_invocations`, with states `PENDING_APPROVAL` to `OUTCOME_UNKNOWN`, single-use nonces and reconciliation. The question is how v2 state fits into that.

Two open inconsistencies in the plan are also settled here: the synchronous deadline (§13.5 says 60 s, §17.2 and ADR [021](021-client-checkin-configuration-menu.md) say 30 s), and which invocations get a database row (§12.1 against §11.3).

## Decision

1. **Control's invocation row is the task store.** v2 adds nullable columns to `control_enterprise_invocations`, plus two tables: `control_business_keys` and `control_invocation_results`. There is no separate task database and no Gateway or Tool Host database role. The exact contents are the migration specifications in `docs/control-plane/migrations-v2/` (Flyway V18, Quickstart V11). The normative behaviour is [invocation-state-v2.md](../control-plane/invocation-state-v2.md).
2. **The v1 state stays authoritative.** `task_state` is a projection of the existing `state` plus the sub-states `INPUT_REQUIRED`, `CANCEL_REQUESTED` and `REJECTED`. A constraint enforces the allowed pairs, so the two can't disagree, and no v1 enum value changes.
3. **Every governed invocation has a row**, as today. Plan.md §12.1 decides only whether that row gets task columns, a business-key row and a result row. "No task record" means none of those three. This settles §12.1 against §11.3, which requires a nonce, and therefore a row, for every non-built-in tool.
4. **Effect safety resolves once.**
   - `requires_execution_ledger = effect_safety IN (NON_IDEMPOTENT, UNKNOWN)` is stored on the row and carried in the permit. A constraint keeps the two in step.
   - The ledger is the existing `RESERVED → EXECUTING` nonce consumption, and the consumed nonce is the fencing token.
   - Ledger invocations are never reclaimed, and a lost lease ends `OUTCOME_UNKNOWN`. Non-ledger invocations with a task may be re-queued, up to 3 attempts.
5. **Business keys live in their own table**, keyed `(tenant, tool, namespace, namespace identity, key hash)`.
   - **Why a table:** a tombstone outlives the invocation's result. This replaces plan.md §12.8's unique index on the invocation table.
   - **One transaction:** the reservation is inserted in the same tenant transaction as the invocation.
   - **Outcomes:** `NEW`, `EXISTING`, `IDEMPOTENCY_CONFLICT`, `IDEMPOTENCY_KEY_IN_USE` (which reveals nothing) and `EXPIRED_KEY`.
   - **Rebinding:** a key may be bound to a new invocation only when Control has proof that the previous one never executed: it expired before consumption, or the host reported `notStarted`. A denied invocation is not rebound.
   - **Retention:** per namespace, then a tombstone kept for at least a year.
6. **Results** up to the 4 MiB output ceiling are stored once in `control_invocation_results`, written idempotently by `(invocationId, fencingToken)`, and later purged to a digest tombstone. The runtime role can't delete them.
7. **Leases.** The default is 30 s, configurable per pool from 10 s to 120 s. The host renews every lease/3 through `POST access/invocations/{id}/heartbeat`, which also carries progress and input requests and returns cancellation and task input. The lease never extends past the permit deadline plus 5 s.
8. **D1 contract amendments found by this review.** They are proposed in `control-v1.proposed.yaml` and `invocation.schema.json`, `permit.schema.json` and `protocol.schema.json`, with fixtures:
   - `InvocationSubmission`, `InvocationAdmission` and `TaskCapability`: the v2 body and response of `POST access/invocations`, which D1 referred to but never defined;
   - `PermitReservationRequest`: the v2 body of `POST access/invocations/reserve`;
   - `TaskView`, `TaskInputSubmission`, `GET access/tasks/{taskId}` and `POST access/tasks/{taskId}/input`: the Gateway needs these to serve `tasks/get` and `tasks/update`;
   - `LeaseRenewal`, and `progress` and `inputRequest` on `LeaseHeartbeat`: the heartbeat response and input relay;
   - `notStarted` on `InvocationResultReport`: plan.md §12.7 row 6's `ABANDONED_BEFORE_START`.

   All of them are additive optional members or new definitions. No existing definition loses or tightens a field.
9. **The synchronous deadline** is `min(platform ceiling 60 s, the Gateway's configured request timeout)`. Release 1's Gateway keeps its 10–30 s request timeout (default 20 s, `apps/gateway/src/config.rs`). So release 1's effective synchronous deadline is at most 30 s, which is the value ADR 021's check-in validation and `tool-sdk/spec/authoring/execution.md` use. The 60 s ceiling applies once the Gateway transport supports longer requests, a later milestone with its own ADR. The two numbers are a ceiling and the current effective maximum, not a contradiction. Plan.md §13.5 and §17.2 now say so.
10. **Quickstart** gets the same state on SQLite, through a runner whose version cap is raised with each migration. Cross-column checks are triggers there, because SQLite can't add table constraints.

## Alternatives Considered

- **A separate task store (database or service).** Rejected (plan.md §12.8): it splits approval and task transitions across stores and loses the single tenant transaction.
- **A new v2 state enum replacing `state`.** Rejected: it would change a v1 contract and every reader. A constrained projection gives the v2 states without that.
- **A unique index for business keys on the invocation table.** Rejected: tombstones must outlive invocation results, and dedupe windows replace rows.
- **Storing only large results out of line.** Rejected: one read path for all task results is simpler, and size is still bounded.
- **Raising the Gateway's request timeout to 60 s in release 1.** Rejected: the Gateway still serves HTTP/1 without keep-alive or SSE. Long work goes through tasks.

## Security Impact

- At most one execution per ledger invocation is enforced by a single-use nonce in Control, not by any component's memory.
- Business-key outcomes for another caller identity reveal nothing: same response, same code path.
- Task access is bound to an owner digest. Any other identity gets `404`.
- Results and reservations are immutable apart from their purge and tombstone. Triggers and grants enforce this as well as the Java code.
- The owner digest and key hash are not secrets. Database read access remains privileged.

## Operational Impact

Two migrations per tree (V18 and V19; V11 and V12) ship with their milestones, and the Quickstart runner cap moves with them. New jobs: lease expiry, input-deadline expiry, retention purge. Control's database grows by result payloads up to their retention. Operators size it like the relay queue, with a 4 MiB per-result bound.

## Compatibility Impact

Additive. v1 rows, endpoints and readers are unchanged. An older image refuses a newer Quickstart database through the runner cap, and Flyway validation does the same for PostgreSQL. The D1 amendments add optional members and new definitions only. One behaviour change: a denied approval now ends the invocation (`CANCELLED`, task `REJECTED`) at once, instead of waiting for it to expire.

## Consequences

- Positive: tasks, ledger and business keys sit in the same transaction as approvals and kills.
- Positive: the migration specifications are executable and tested on both databases before any code exists.
- Negative: Control's invocation table gains 18 columns, and its row becomes the hot spot for long-running work.
- Negative: result payloads in PostgreSQL need retention discipline.

## Validation

- `tests/contracts/test_migration_specs_v2.py` applies the current Flyway and Quickstart trees plus the specifications, and runs shared accept/refuse scenarios on SQLite always and on PostgreSQL when `TOOLGATE_D2_POSTGRES` is set. Both passed for this gate, on PostgreSQL 16 and SQLite 3.45.
- `tests/contracts/test_contracts_v2.py` covers the amended schemas and fixtures.
- D3 authors conformance cases for every transition in invocation-state-v2.md §3, including the ambiguous-dispatch, lease-expiry, rebind, late-result and cross-identity cases.
