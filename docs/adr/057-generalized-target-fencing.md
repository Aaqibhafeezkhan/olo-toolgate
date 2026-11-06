<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 057: Generalized target fencing and shared claim recovery

**Status:** Accepted for the bounded S21 delivery; no full roadmap phase acceptance.

S21 extends the exact reviewed generic HTTP route with a dedicated target
identity and one of two declared implementation classes: shared SQL business
transactions, or SQL claim ownership around external actions. A target-specific
authenticated Control role may consume only its own signed exact-request grants.
The grant binds the accepted executor mTLS public key. Revocation committed
before first consumption refuses the request. Consumption replays are status,
never fresh authority. The broker alone transports the reserved permit header;
workers cannot supply or read it.

Both classes require durable shared claims, a unique owner, an APPLYING CAS and
retained outcome delivery. The SQL class may atomically commit business data and
completion. External actions must commit APPLYING first; a crash or uncertain
action remains unresolved until independent target evidence exists. Neither
class turns an HTTP response into independent business finality. Native MCP
remains outside this generic HTTP qualification.

Use only fresh current contracts and schemas. No migration, backfill, conversion,
legacy alias, compatibility constructor or fallback is introduced. Current-schema
crash recovery remains mandatory. This constraint supersedes compatibility or
migration suggestions in ADRs 042/044 and the preserved proposal.

Stage completion requires signed request/peer/target refusal vectors, actual
Control consumption and revocation ordering, two-replica ownership, paused-owner
takeover, apply/recovery races, crash boundaries, lost replies and durable outcome
delivery against SQLite and PostgreSQL, plus affected broker and full component
verification. The authoritative progress document records actual evidence.
