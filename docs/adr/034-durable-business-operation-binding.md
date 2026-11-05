<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 034: Durable explicit business-key binding

**Status:** Accepted for the single-attempt operation slice of issue #230.

## Decision

Explicit business keys resolve to one immutable operation/invocation in Control,
independently of transport request IDs. Gateway hashes the caller key with
`SHA256("toolgate-business-key-v1" + NUL + tenantId + NUL + key)` and forwards
only that lowercase hex digest. Control independently derives
`op-` plus the lowercase SHA-256 of the newline-separated domain
`toolgate-operation-v1`, tenant UUID, tool ID, namespace, namespace identity and
key hash. Identity components are validated identifiers without newlines.

Agent calls use the authenticated AGENT namespace; calls without an agent use
the authenticated DELEGATED_USER namespace. No caller-selected namespace or
owner is accepted. AGENT bindings retain the original user, so the same agent
acting for another user receives a conflict before fingerprint comparison or
result disclosure. TENANT sharing is not enabled.

The existing tenant transaction inserts the invocation, business-key reservation,
budget reservations and audit together. SQLite serialization/PostgreSQL tenant
locking and unique constraints serialize competing replicas. Exact-input replay
still checks current policy and credential epochs. The stored fingerprint binds
the complete existing invocation request digest (arguments, code, resources,
action, caller and binding), deliberately stricter than package-major-only
matching. Credential rotation and trace correlation do not change that digest.

Terminal invocation transitions update the business-key outcome in the same
transaction. Unknown keyed operations cannot use the legacy same-invocation
DOWNSTREAM_IDEMPOTENCY resend path: new attempts must eventually have separate
records and the phase-3 target-deduplicated protocol. This implementation never
rebinds a consumed, failed, expired or missing operation. Thirty-day retention
expiry and existing tombstones refuse reuse; no runtime deletion is introduced.

## S09 extension

[ADR 047](047-proof-bound-operation-rebinding.md) extends this original
single-attempt slice with positive-proof, original-deadline whole-operation
recovery. It preserves the operation ID and key owner/fingerprint while assigning
a separate immutable attempt nonce. The original no-rebinding limits below apply
to the foundation; the later qualification and no-ambiguity rules govern recovery.

## Compatibility and limits

`EnterpriseInvocationRequest.businessKeyHash` is optional. Generated Java retains
the six-argument constructor. Calls without a key retain request-ID behavior.
Native Gateway admission now returns a stable `op-...` ID for its required
Idempotency-Key; clients must use the returned ID. Historical `inv-...` bindings
cannot be backfilled without their raw keys. Deployment therefore requires a
coordinated cutover, settling old workflows on their existing IDs and avoiding
mixed old/new admission replicas for one workflow. The operator guide makes this
compatibility limitation explicit; this slice does not claim transparent upgrade
of historical keys. MCP opts in through
`_meta["io.ololabs.toolgate/businessKey"]`; keyed cancellation accepts the returned
operation ID and Control checks the original actor before changing state.
Unknown operation IDs cannot create cancellation tombstones.

This is conservative single-attempt binding, not full phase-1 completion.
Generalized tool-policy enforcement of requiresBusinessKey, argument keys,
DEDUPE_WINDOW, configurable retention, tombstone maintenance, safe unconsumed
rebinding, separate attempt records, all-attempt aggregation and per-effect
recovery remain required. No exactly-once external effect guarantee is made.
