<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 047: Positive-proof operation rebinding

**Status:** Accepted; S09 delivered with scoped SQLite/PostgreSQL/OCI evidence. Full roadmap phase acceptance remains outstanding.

## Decision

Keep the caller's logical operation ID, business-key owner/fingerprint and original
deadline stable. A replacement execution receives a new permit nonce/attempt.
Retain old attempts, snapshots, effects and late evidence. Control assigns the
attempt ordinal, bounds an operation to 64 attempts, and uses opaque nonces with
an ordinal prefix so equal-millisecond issuance still has deterministic order. Never rewind an old
attempt or renew its nonce. This amends the prospective invocation-state design's
new-invocation-ID wording: separate immutable permit attempts already provide the
execution identity without changing the operation's public ID.

Rebinding needs a fresh current-authority decision in the tenant writer transaction
and a durable, immutable recovery record binding the previous invocation revision,
every predecessor attempt, proof kind, original deadline and replacement revision.
Its insertion, operation transition, business-key outcome transition and audit
commit atomically. A duplicate or racing request observes the winner. Rollback
leaves no replacement authority. Cancelled, rejected, completed, partially completed,
expired-deadline and legacy operations cannot reopen.

Positive evidence has two paths. Control's complete execution-permit journal may
prove every issued permit expired without consumption. Missing Control operation,
nonce/attempt bindings or unavailable storage is not that proof. An executor's
not-started report, missing spool or silence never substitutes for it.

For consumed attempts, Control additionally needs immutable admission-time scope:
an operator-qualified authenticated-device/pool/image pin and independently pinned Python OCI runtime,
no unmediated permissions or worker credentials,
and exclusively supported broker-mediated OLO routes whose writes all use the
durable authorization-generation protocol. Capture scope from the independently
validated package/profile at issuance, never from a worker report or caller flag.
Current native/credential/tunnel/general HTTP routes do not inherit this scope.
Every generation for every predecessor must be RELEASED; absent qualification,
SENDING, consumption, ambiguity, completion or incomplete history stays fenced.
An empty effect set is usable only with this positive complete-mediation scope.

Preserve the original deadline and key retention; a retry cannot buy more time.
Recheck current grants, epochs, approvals, code/binding and cancellation. Retained
late evidence for an old nonce cannot settle or authorize the replacement.
Caller history/aggregate outcome presentation belongs to S10; no report becomes
effect-verified success merely because a replacement is possible.

## Verification

First exercise the proof policy with malformed/incomplete facts and every unsafe
predecessor. Then exercise authoritative SQL evidence, immutable admission scope,
recovery guards, atomic rollback, competing Control instances and stale/late
permits on SQLite and PostgreSQL. Verify runtime/profile qualification against real
signed admission and actual OCI execution. Complete the stage-level gates before
one S09 commit with the authoritative progress update. These checks now pass;
the [authoritative status](../security/zero-trust-execution-status.md) records
executed results, known parent/source digests, the first OCI timing failure and
successful isolated rerun. This does not accept the full security roadmap phases.
