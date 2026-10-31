<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Effect authorization generations

Control V4 adds an effect ledger. Existing OLO **write** service permits now reserve
an effect generation in the same transaction as the service nonce. Target
consumption commits that generation's CONSUMED state with nonce consumption,
after the existing identity, fingerprint, lease and current-policy checks.
Read capabilities retain their existing signed/epoch boundary.

An effect has a stable operation/route identity and immutable request digest.
Authorizations have separate generations, nonces and dispatch owners. The internal
store reports `newAuthority=false` on replay: an observed status is not another
send grant. Only RELEASED can create a new generation. An expired authorization
without a release becomes AMBIGUOUS, even if no response was seen.

| State | Meaning |
|---|---|
| AUTHORIZED | Reserved for the recorded device/broker/handler; not sent. |
| RELEASED | Owner durably voided this generation before sending; nonce never regains authority. |
| SENDING | Owner crossed the single-send point; cannot release or send again. |
| CONSUMED | Target accepted authorization; its business outcome remains pending. |
| AMBIGUOUS | Authorization expired without release, send/consumption timed out, or the invocation ended. |
| COMPLETED / FAILED | Internal target-confirmed outcome evidence resolved the consumed generation. |
| CONFLICT | Contradictory target outcomes/receipts were retained; no further authority is granted. |

Broker observations are retained independently and cannot settle a consumed
fenced effect. Late target outcomes are accepted only for the recorded target and
claim; different claims or request digests are refused. Evidence rows cannot be
updated/deleted. PostgreSQL enforces forced tenant RLS and the schema ownership
manifest includes all new tables.

## Current scope

The live OLO adapter uses the execution nonce and service nonce as stable legacy
broker/handler identifiers. Its request digest covers the existing signed service
identity, operation, resource and body fingerprint binding. It does **not** yet
cover the full HTTP transport. General broker instance/handler identities, exact
HTTP request binding, effect keys for several logical writes, consent slots and a
generalized broker API remain required.

Existing repeat service consumption still refuses execution; the target-side
shared claim store/SDK and status-returning consume API are not implemented by
this increment. Internal target-outcome methods are tested but no new public
outcome endpoint is exposed yet. Do not infer target deduplication or final
operation success from CONSUMED. Existing operations remain conservative UNKNOWN
after consumed terminal attempts.

## Upgrade

Upgrade Control replicas together. V1–V3 files stay unchanged; V4 adds the new
ledger and SQLite refuses downgrading V4 state to older images. Historical write
capabilities with no effect generation are refused, not granted inferred history;
allow their existing 15-second lifetime to drain during cutover. Read permits keep
the old epoch behavior. The background execution watchdog performs bounded
expiry-to-ambiguity updates (100 rows per pass), and terminal invocation updates
abandon all outstanding generations for that operation. Expired permits cannot
consume even before a watchdog pass.

The prospective kill-switch draft moves to V5 without content changes. See
[ADR 036](../adr/036-effect-authorization-generations.md) and the
[live progress document](../security/zero-trust-execution-status.md).
