<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 035: Independent operation and permit-attempt journal

**Status:** Accepted for the operation/attempt foundation of issue #230.

## Decision

Persist one operation per existing invocation, one immutable attempt identity per
reserved permit nonce, and append-only attempt snapshots. The nonce is the attempt
ID and fencing token; the invocation ID remains the stable operation ID. Existing
wire contracts and Gateway IDs do not change. Admission has an operation record
before a permit attempt is reserved. Each issued permit, including an expired
unconsumed predecessor, retains its own identity and observations.

V3 adds an immutable nonce purpose: EXECUTION for worker admission, SERVICE for
existing OLO service capabilities, LEGACY for historical/older inserts. Only
execution reservations create new worker-attempt identities. Service permits keep
their existing independent single-use authority checks. Historical purpose cannot
be proved, so V3 conservatively journals retained LEGACY nonces as potential
attempts on SQLite/PostgreSQL.
Historical consumed predecessors have unknown outcomes rather than inheriting a
newer attempt's result. Original historical snapshots cannot be reconstructed;
the backfill is marked legacy and never presented as complete historical proof.
New invocation transitions, nonce reservations and journal writes commit in the
same tenant transaction. Existing immutable execution evidence remains attached
by operation and nonce, including late evidence that cannot reopen execution.

Only positive Control evidence permits a replacement: a prior reservation's
nonce exists, expired, and was never consumed. Database nonce guards additionally
refuse another reservation while any predecessor was consumed, is live, or lacks
an attempt binding. The legacy DOWNSTREAM_IDEMPOTENCY same-row resend is disabled
for all operations, including unkeyed calls. Target-deduplicated retries require
the phase-3 protocol; a declaration alone cannot establish those guarantees.

Operation summaries are conservative: active invocation/attempts are RUNNING;
ambiguous or consumed terminal attempts are OUTCOME_UNKNOWN. A new operation can
be finalized FAILED only if it ended without consuming any permit and its durable
journal accounts for every permit. Legacy terminal operations remain unknown.
Worker-reported success is still visible through the legacy invocation API, but
it does not establish a final effect-verified operation success. Phase-3 manifests,
effect resolution and reconciliation are required before that can be asserted.

## Limits and compatibility

No new execution authority or automatic retry after consumption is introduced.
Safe reservation replacement stays inside the original invocation deadline;
whole-invocation rebinding, effect-set finalization and public history presentation
remain later work. Existing result/lease APIs keep their identifiers. Downgrading
a V3 SQLite database to older images is refused. PostgreSQL runtime roles receive
no delete privileges on the new journal; forced tenant RLS and immutable identity
and snapshot guards apply. History is available through the internal store and
operator SQL, not a new caller API in this increment.
