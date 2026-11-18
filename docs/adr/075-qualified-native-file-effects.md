<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# 075: Qualified native filesystem broker effects

Status: accepted; bounded fresh-stack Linux file-only A/B acceptance and SQLite/PostgreSQL component checks pass. Full roadmap/security acceptance remains outstanding.

The managed Linux Java worker has no filesystem mount or network. Its protected
device owner is the actual filesystem target, and mediates requests over private
IPC. A tool result is never a receipt. Qualify this tier only through an explicit
live Super Admin decision for an exact device, administered group and immutable
managed image, with expiry. Defaults grant no qualification. Package descriptors
must be synchronous, closed and file-only, without network, credentials or globals.
Qualification is installation policy, never authority imported from a profile.

Before each WRITE or RENAME, the broker obtains a deterministic, tenant/attempt/
sequence-bound native effect intent in Control using device mTLS and its current
consumed execution lease. Paths and content digests must match original admitted
arguments. Control records a logical effect and manifest membership before the
filesystem operation. Native effects have their own intent/receipt records;
they do not manufacture HTTP generations, target deduplication or fencing.

After durable application, the protected owner reports content witnesses. WRITE
must leave the exact admitted text digest. RENAME must preserve source content,
remove the source and create an absent destination. Directory fsync is required
for supported Linux mutations. An uncertain operation poisons further mutations
within that invocation. No effect is automatically retried. Missing receipts,
broker loss, missing scope closure or conflicting witnesses remain unresolved.

After the child and broker have terminated, the owner closes the native scope.
Only a closed, qualified scope participates in settlement/finalization. All
admitted native effects appear in the same attempt manifest and final outcome,
with unfenced classification. The existing SDK completion report still determines
whether the worker completed; it cannot create filesystem witnesses.

This qualifies an operator-vetted protected owner, not a compromised device OS,
remote attestation or exactly-once filesystem semantics. Read-only qualification
uses the same explicit live policy and closed-scope evidence. Unsupported file
primitives are refused for qualified execution until separately implemented.

Use fresh current schemas and canonical contracts only, with tenant RLS,
immutable evidence, capacity accounting and SQLite/PostgreSQL negative tests.
Existing stores are preserved and refused; no migration/adoption is implemented.
The demo must retain independent Control outcomes and actual file observations,
and must not claim the wider #230/#274/#295 roadmaps complete.
