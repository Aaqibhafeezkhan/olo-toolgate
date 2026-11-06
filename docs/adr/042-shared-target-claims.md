<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 042: Shared SQL target claim ownership

**Status:** Accepted for the SQL claim-store foundation.

Every replica of a deduplicating target must share one durable claim database.
The target first reserves a tenant/effect key with the exact authorization
generation, nonce and request digest. A unique insert returns a random owner
token only to its winner; losing replicas receive status without a token.

The owner must CAS CLAIMED to APPLYING before any business action. Whenever
possible, that CAS, the mutation and DONE/FAILED result digest commit in the
same target transaction. The store never commits a caller transaction itself.
A recovery process may CAS CLAIMED to ABANDONED while advancing ownership
generation; a paused former owner can no longer enter APPLYING. Recovery from
APPLYING cannot report not-started and never grants a new owner automatically.
DONE/FAILED and ABANDONED claims remain durable tombstones; no deletion API is
provided. Deployments must retain them across replicas and restarts.

Use caller-owned writable JDBC transactions and fresh current target schemas.
No upgrade, backfill or compatibility path is supported (ADR 057).
SQL failures roll back and grant no authority; the library retries nothing.
SQLite and PostgreSQL are the first qualified adapters. The SQL claim store
does not authenticate requests: bindings must come from verified Control grants.
This foundation alone does not change consumption replay, expose outcomes, or
integrate the memory/RAG transaction boundary. Those integrations must execute
conformance before claiming fenced-with-deduplication target guarantees.
