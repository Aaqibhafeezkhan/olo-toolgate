<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 044: Verified target claims and durable outcome delivery

**Status:** Accepted for the OLO memory/RAG integration.

Separate verified write facts from authorization to commit a mutation. Only
signature, accepted mTLS peer, identity, exact body and transport verification
may produce the SDK's verified-write object. The target commits its shared local
claim before Control consumption. Only the unique claim owner may consume and
CAS into APPLYING; a duplicate returns status without mutating business data.

Commit APPLYING before business actions, including external vector writes.
RAG's SQL publication gate does not make external I/O transactional. Failed or
uncertain work after APPLYING remains uncertain until conclusive target evidence
exists. Recovery never reports not-started from APPLYING.

Business completion and an outcome outbox record share the target SQL
transaction. The original authorization deadline is checked before commit.
Outcome delivery is authenticated, idempotent and may happen late; losing an
acknowledgement retries only outcome reporting, never the business action.
Recovery may abandon an expired CLAIMED generation by CAS, then enqueue its
not-started evidence in that same transaction. Reporting occurs only after
commit. A paused owner cannot subsequently enter APPLYING.

Add an expiry column compatibly to existing SDK claim stores; legacy rows with
unknown expiry are not automatically abandoned. Retain claim/outbox tombstones.
Actual target migrations enforce tenant RLS and deny runtime deletion of these
records. Missing schema fails startup instead of falling back to one-shot writes.
Reads retain current authorization and release checks.

The first integration remains one authorization generation per OLO effect;
abandoned/uncertain work grants no automatic replacement. General route grants,
whole-operation finalization and full-stack/deployment conformance remain
separate acceptance work.
