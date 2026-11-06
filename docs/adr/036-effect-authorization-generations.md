<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 036: Durable effect authorization generations

**Status:** Accepted for phase-3 ledger foundation; not full phase-3 acceptance.

## Decision

Store stable effect identities and request digests separately from authorization
generations. Each generation binds its execution attempt, device, broker instance,
handler, unique authorization nonce, authorization expiry and outcome deadline.
Only a durably RELEASED generation may be superseded. Expired AUTHORIZED work is
AMBIGUOUS, never proof of no send. SENDING and CONSUMED are single-use transitions;
consumption is not an outcome. Identity/fingerprint changes fail closed.

Integrate the first live writer with existing OLO service write permits: issuance
and effect authorization commit together; target nonce consumption and effect
consumption commit together after existing current-authority checks. Existing
repeat consumption still refuses execution. Legacy read permits keep their current
narrow epoch boundary. No claim is made that the old service binding covers all
HTTP bytes; complete request binding is the next task.

The internal ledger supports owner-bound release/send, target-bound consumption,
late target outcomes, immutable evidence and timeout-to-ambiguity. Broker evidence
cannot settle a consumed fenced effect. Conflicting target outcomes are retained
and put the effect into a reconciliation-required state without granting another
authorization. These internals do not yet expose a generalized broker API or a
target shared-claim SDK. No target deduplication guarantee is introduced.

V4 adds forced tenant RLS, immutable identity/state guards and retained evidence
on PostgreSQL and SQLite. Existing V1–V3 migrations stay unchanged. The pending
kill-switch specification moves to V5 with identical contents. Historical signed
write capabilities without a ledger generation are refused after the cutover;
upgrade Control replicas together and allow the existing 15-second capabilities
to drain. Missing history never creates new execution authority.
