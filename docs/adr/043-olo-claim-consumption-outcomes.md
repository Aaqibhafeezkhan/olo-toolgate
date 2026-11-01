<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 043: OLO claim-token consumption and late target outcomes

**Status:** Accepted for the bound OLO write protocol.

Bound OLO write permits carry the immutable effect ID and authorization
generation. A target verifies the signed scope and accepted HTTP facts, commits
its shared local claim, then consumes the grant using its random claim token.
Control checks current authority only for the first consumption and commits the
token with the existing nonce and effect generation atomically.

Consumption replay returns status, never a fresh execution capability. A
different token receives CONSUMED_BY_OTHER_CLAIM. Execution still requires the
target's local ownership CAS into APPLYING. Legacy one-shot consumption keeps its
existing refusal of repeats; it does not acquire deduplication guarantees.

Authenticated target status and outcome requests use the recorded nonce and
claim token rather than requiring the expired original permit. They remain
usable after grant expiry, invocation termination and signing-key rotation.
They grant no dispatch authority. The dedicated audience service identity,
tenant, recorded consumer and claim token must match. Outcomes append existing
target evidence; contradictory outcomes become CONFLICT. Broker observations
cannot settle a consumed fenced effect.

Upgrade target contracts/SDK before Control starts adding signed metadata.
Drain old short-lived grants during coordinated rollout. This protocol delivery
does not itself integrate the shared claim store into memory/RAG transactions,
generalize non-OLO grants, implement consent slots or finalize whole operations.
Those guarantees require their subsequent integration and conformance.
