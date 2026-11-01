<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Shared target effect claims

JdbcEffectClaims in toolgate-service-authorization provides a target-owned SQL
claim state machine. It does not verify a grant or call Control. Supply bindings
only after verifying the signed tenant, effect ID, authorization generation,
nonce and exact request digest. ControlServiceAuthorization.verifyWrite supplies
those immutable facts without a commit authorization. Actual OLO adapters adopt
this workflow; installing the library alone adds no target guarantee.

All replicas must use the same database and retain claims across restarts.
For a generic SDK store, provision the claim/outcome schema with initialize in
a writable deployment migration transaction. Actual OLO deployments use the
provided target-claims.sql migration with forced RLS and restricted runtime
grants before accepting requests. The application must not delete claims,
reset ownership, or silently fall back to an in-memory store. Back up and restore
claims consistently with business data. Database exceptions grant no authority.

1. Call claim in a writable caller-owned transaction and commit it. Only an
   acquisition with a non-null owner token may proceed. Losing replicas return
   status, not permission to act. Changed bindings fail closed.
2. The claim owner consumes the corresponding authorization at Control.
   Control now provides claim-token consumption replay and late target outcomes;
   the OLO target adapter now uses them with its durable local claim.
3. Call beginApply with that owner before any business action. Abort if its CAS
   fails. Prefer one transaction containing beginApply, the business mutation,
   finish and the authorization deadline check immediately before commit.
4. Record the durable DONE or FAILED result digest and report the outcome using
   the target identity using Control's authenticated claims/outcome API.

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

No retention/deletion policy or automatic takeover from APPLYING is provided.
The OLO memory/RAG integration now commits APPLYING before business actions,
including external RAG vector I/O, and records completion/outbox with SQL
publication. It returns HTTP 409 status for duplicates without a mutation.
Schema migration, forced tenant RLS, immutable-binding/transition guards and
restricted runtime privileges are required; missing or unsafe schema fails
target startup. Follow the target component's deployment guide. See
[ADR 042](../adr/042-shared-target-claims.md) and the
[live status](../security/zero-trust-execution-status.md).


## Recovery and delivery

The additive SDK migration adds authorization_expires_at and the
toolgate_target_outcomes outbox. Legacy claims receive expiry 0 and are never
automatically abandoned. Target deployment migrations run as the schema owner;
the ordinary runtime cannot initialize schema, delete tombstones or rewrite
claim bindings. Grant only the documented transition/delivery columns.

For external actions, commit APPLYING before the action. Target business
completion and its outcome delivery intent share one SQL transaction.
A crash or lost response after commit leaves a durable outcome; retry reporting,
not the business mutation. The OLO reporter uses bounded, coalesced passes and
keyset rotation. The target identity reports after the original permit expires;
delivery acknowledgement changes no execution authority.

Expired CLAIMED recovery atomically commits ABANDONED plus not-started evidence
before reporting. A paused owner then fails its APPLYING CAS. APPLYING remains
uncertain without conclusive target evidence. If Control never recorded the
claim, it refuses an outcome for that unknown claim; the evidence stays queued
for reconciliation and grants no resend. There is no automatic replacement of
an abandoned or uncertain OLO effect. Whole-operation finalization, generalized
routes and deployment SLO acceptance remain separate work.
