<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Shared target effect claims

JdbcEffectClaims in toolgate-service-authorization provides a target-owned SQL
claim state machine. It does not verify a grant or call Control. Supply bindings
only after verifying the signed tenant, effect ID, authorization generation,
nonce and exact request digest. The current OLO verifier integration has not yet
adopted this store; installing the library alone adds no target guarantee.

All replicas must use the same database and retain claims across restarts.
Provision toolgate_target_claims using initialize in a writable migration
transaction before accepting requests. The application must not delete claims,
reset ownership, or silently fall back to an in-memory store. Back up and restore
claims consistently with business data. Database exceptions grant no authority.

1. Call claim in a writable caller-owned transaction and commit it. Only an
   acquisition with a non-null owner token may proceed. Losing replicas return
   status, not permission to act. Changed bindings fail closed.
2. The claim owner consumes the corresponding authorization at Control.
   This foundation does not yet implement claim-token consumption replay.
3. Call beginApply with that owner before any business action. Abort if its CAS
   fails. Prefer one transaction containing beginApply, the business mutation,
   finish and the authorization deadline check immediately before commit.
4. Record the durable DONE or FAILED result digest and report the outcome using
   the target identity once the public outcome integration is available.

A restarting replica can inspect a claim without retrieving its owner token.
For CLAIMED, recovery may abandon the observed ownership generation. Commit the
successful CAS before reporting not-started. It advances the generation and
permanently fences a paused old owner. ABANDONED does not grant a replacement.
For APPLYING, abandon refuses: inspect the target's own effect state and preserve
uncertainty unless conclusive evidence exists. DONE/FAILED results remain status.

SQLite and PostgreSQL are the initial SQL dialects. Use separate JDBC
connections for concurrent transactions. The adapter uses explicit unique
inserts and compare-and-set updates and performs no automatic retries or commits.
SQLite callers should configure their normal busy timeout; lock failures require
rollback and grant no execution authority. PostgreSQL READ COMMITTED is the
qualification baseline. Stronger isolation may reject transactions; handle that
as failure, without dispatching an effect.

No retention/deletion policy, automatic takeover from APPLYING, outcome HTTP API,
or full memory/RAG integration is delivered by this foundation. See
[ADR 042](../adr/042-shared-target-claims.md) and the
[live status](../security/zero-trust-execution-status.md).
