<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 060: Tenant audit hash chains

**Status:** Implemented for issue #230 stage S24; no phase-4 acceptance.

This implements the first part of the preserved execution proposal's §4.6 item 6
("Tamper evidence") and gap G9. Signed chain heads and external anchors are ADR 061
(S25); offline `toolgate audit verify` is S26. The fresh-build constraint applies:
the chain columns are folded into the fresh initial SQLite and PostgreSQL schemas.
There is no migration, backfill or conversion of pre-chain audit rows. Stores that
predate the current schema are refused by the existing fresh-schema checks (SQLite
schema checksum, Flyway checksum validation) without being converted or deleted.

## Chain shape

Every `control_audit` row belongs to exactly one tenant chain and stores:

- `chain_index`: 1-based, contiguous, unique per tenant;
- `previous_digest`: the predecessor's `row_digest`, or 64 zero characters for index 1;
- `row_digest`: the domain-separated canonical SHA-256 of the row (below).

`sequence` remains the global row identity and cursor for the existing paged audit
API; it is not part of the chain and verification never relies on it.

## Canonical digest

`row_digest = lowercase-hex(SHA-256(encoding))`, where `encoding` is the ASCII
domain `toolgate/control-audit-chain/v1\n` followed by, in order:

| Field | Encoding |
|---|---|
| tenant id | u32 big-endian byte length + UTF-8 |
| chain index | i64 big-endian |
| previous digest | length-prefixed UTF-8 |
| actor id, operation, target | length-prefixed UTF-8 each |
| revision | i64 big-endian |
| request id, request digest | length-prefixed UTF-8 each |
| evidence | `0x00` when absent; otherwise `0x01` + length-prefixed UTF-8 of the stored JSON text |
| occurred at | i64 big-endian Unix epoch milliseconds |

The tenant id inside the digest makes a row meaningless in any other tenant's chain,
so cross-tenant splicing is detectable even at index 1, whose predecessor is the
shared zero genesis. Length prefixes make field boundaries unambiguous, and absent
evidence differs from empty evidence.

Writers supply `occurred_at` explicitly at millisecond precision; neither schema
has a database default. SQLite stores the exact text `yyyy-MM-ddTHH:mm:ss.SSSZ`;
PostgreSQL stores the same instant. A verifier treats non-canonical text or a
sub-millisecond PostgreSQL value as malformed. `actor_id` and `request_digest` are
`varchar(64)` in PostgreSQL so that short internal actors such as `control` are
stored and hashed byte-for-byte, without `character(n)` padding.

The Java writer (`AuditChain`) and the Python writer used by Quickstart identity
audit and offline restore (`apps/quickstart/audit_chain.py`) share fixed test
vectors.

## Serialization and database guards

Appends are serialized per tenant across replicas:

- **PostgreSQL:** the writer takes `pg_advisory_xact_lock(hashtextextended(tenant
  || '/audit-chain', 0))` before reading the head. Control write transactions
  already hold the per-tenant write lock; the dedicated key also serializes the
  Python writers. A `BEFORE INSERT` trigger takes the same lock and refuses any row
  whose index or predecessor does not extend the committed head (SQLSTATE 23514).
  A unique `(tenant_id, chain_index)` constraint backs it. A separate
  `BEFORE UPDATE OR DELETE` trigger refuses changes, in addition to the runtime
  role's `SELECT, INSERT` privileges.
- **SQLite:** Control writers hold the single `IMMEDIATE` write reservation;
  Quickstart identity audit begins `IMMEDIATE` before appending. A `BEFORE INSERT`
  trigger refuses non-extending rows; existing update/delete triggers keep rows
  immutable.

These guards keep honest writers linear. They do not stop the database owner (A6),
who can disable triggers. Detection of A6 rewriting is the chain's purpose.

## Verification

`AuditChain.verify(tenant, start, rows, expectedHead)` walks rows in chain order
and reports the first defect: malformed row, foreign tenant, missing or reordered
index, broken predecessor link, edited content (digest mismatch), truncated tail
or head mismatch against an expected head. A chain alone cannot see tail
truncation or a consistent rewrite of the whole suffix; that requires an
independently retained head. S25 signs and externally anchors heads for that
purpose. Segment verification continues from a previously verified head.

## Threat coverage and limits

The chain detects A5 changes made through the database and A6 rewrites before the
last independently retained head. It cannot stop A6 from appending false rows
while in control or from rewriting the unanchored suffix consistently. Tenant
offboarding in reviewed fleet recovery deletes a complete tenant chain only for
unselected tenants of a fresh restore target. Reviewed restore appends its
`RESTORE_QUARANTINE` record as the next link of the restored tenant chain.

## Required evidence

SQLite and owned-PostgreSQL runtime-role tests: concurrent appenders on two store
instances and two tenants produce one contiguous chain each; forks, updates and
deletes by the runtime role are refused; owner edits, recomputed-digest edits,
reordering, cross-tenant splicing, middle deletion and tail truncation are
detected. Cross-language vector parity and Python SQLite/PostgreSQL writer checks.
