<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 061: Signed external audit anchors

**Status:** Implemented for issue #230 stage S25; no phase-4 acceptance.

This builds on [ADR 060](060-tenant-audit-hash-chains.md) and implements the
remaining signing/anchoring part of the preserved proposal's §4.6 item 6. Offline
`toolgate audit verify` remains S26. Fresh schemas only: the anchor table is part
of the initial SQLite and PostgreSQL schemas, with no migration or backfill.

## Anchor document and signature

Control periodically signs each active tenant's committed chain head. The signed
document is the exact compact JSON, with keys in sorted order:

```json
{"chainIndex":42,"formatVersion":1,"headDigest":"<64 hex>","issuedAtUnixMs":1762560000123,"keyId":"anchor-2026-11","purpose":"AUDIT_ANCHOR","tenantId":"<uuid>"}
```

The signature is RSASSA-PKCS1-v1_5 with SHA-256 over the ASCII domain
`toolgate/audit-anchor/v1\n` followed by the document's UTF-8 bytes, encoded as
unpadded base64url. Verifiers re-serialize parsed claims and refuse any document
that is not byte-identical (extra fields, whitespace, other purposes or versions).
The domain prefix and the fixed `purpose` keep anchor signatures distinct from
every other signed artifact even if a key were misused.

## Dedicated key domain and lifecycle

The anchor key is a separate custody domain (`AUDIT_ANCHOR`), configured with
`toolgate.control.audit-anchor.private-key-path` and `key-id`. Startup fails
closed when anchoring is enabled and the key is not an RSA 3072/4096-bit key with
e=65537, is not the pinned keyring's single ACTIVE key, or shares a modulus with
any other configured Control custody: identity verification, bundle, fleet,
package-release and effect/permit signing keys, TLS and device CA certificates,
release/organization/recovery trust and the 2-of-3 operator approval keys.

Verification uses a **pinned keyring** supplied outside Control's database
(`keyring-path`): `{"formatVersion":1,"purpose":"AUDIT_ANCHOR","keys":[...]}`, each
key with `keyId`, RSA `n`/`e`, `state` and `activatedAtUnixMs`, plus
`retiredAtUnixMs` for non-active keys. Rules:

- at most one ACTIVE key; Control signs only with it;
- a key verifies only anchors issued within `[activatedAtUnixMs, retiredAtUnixMs)`;
- rotation marks the previous key RETIRED with a retirement time, so earlier anchors
  remain verifiable while anything it signs afterwards is refused;
- a REVOKED key verifies nothing, including earlier anchors; an unknown key is refused;
- moduli must be distinct and keys strong.

A revoked key's anchors lose their meaning; Control then verifies the full chain
from genesis before signing a fresh head with the new active key.

## Sinks

`AuditAnchorSink` is the pluggable external publication port. Publication is
write-once: identical bytes are idempotent, different bytes for an existing name
are a conflict, and no operation deletes or replaces an object. Object names are
deterministic per tenant and head
(`tenants/<tenant>/audit-anchors/<19-digit index>-<head digest>.json`), so a retry
after a crash between publication and the database record converges on the same,
re-verified object. Concurrent replicas anchoring the same head converge the same
way: one publication wins, the other sees a conflict or reuses the verified object,
and the primary key on `(tenant_id, chain_index)` records it once.

- **Local append-only directory (shipped).** Objects are staged, synced and created
  with an atomic hard link that fails if the name exists, then made read-only. The
  directory should be a separately administered mount that Control can create in
  but not rewrite (for example a WORM-capable volume).
- **Customer bucket with object lock (specified).** Conditional create
  (`If-None-Match: *`) into a customer-owned bucket with compliance-mode object lock
  and a retention period longer than the audit retention requirement.
- **Transparency log (specified).** Append of the anchor object; the receipt carries
  the log's signed inclusion promise or proof for offline verification in S26.

## Anchoring procedure

For each active tenant the scheduler (`interval-seconds`, default 300):

1. reads the committed head and the latest recorded anchor;
2. verifies the recorded anchor against the pinned keyring and the chain segment
   from that anchor to the head (from genesis on the first anchor or after a
   revocation);
3. refuses to sign if the segment is broken, logging `audit_chain_broken`;
4. signs, publishes to the sink and only then records the anchor in the immutable
   tenant-scoped `control_audit_anchors` table.

Anchoring writes no audit row, so the head does not advance by itself. The database
record is a convenience for scheduling; it is not trusted. A rewritten record fails
signature verification and stops anchoring for that tenant.

## Threat coverage and limits

An anchor fixes a tenant's chain up to its head outside the database. Rewriting any
row at or before an anchored head, including a consistent recomputation of every
later digest, is detected as a head mismatch against the sink copy. A6 can still
append false rows while in control and can rewrite the unanchored suffix since the
last anchor; the anchoring interval bounds that window. A6 with the anchor private
key can sign false heads; custody separation, revocation and the external sink's own
retention are the controls. The shipped local sink is only as independent as the
mount it uses.

## Required evidence

Signature/claim tests: domain separation, tenant binding, unknown/revoked keys,
rotation windows, keyring hygiene. Sink tests: write-once, idempotent republish,
conflict, read-only objects and name confinement. SQLite and owned-PostgreSQL tests:
periodic anchoring, crash-retry convergence, anchor/chain mismatch after a
consistent rewrite, refusal to sign a broken chain, rewritten anchor records and
runtime-role immutability.
