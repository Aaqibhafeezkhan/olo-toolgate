<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Derived operation outcomes and history

The bounded S10 finalization feature is implemented and qualified under the
explicit stage scope amendment. See the authoritative
[execution status](../security/zero-trust-execution-status.md) for executed checks
and remaining delivery criteria.

An invocation's state records the executor report. Its optional operationOutcome
is a separate Control projection over every attempt and effect generation.
A successful worker response alone does not establish business success. The
projection orders conflict/extra writes before active work, then unresolved
effects or unsettled attempts, before final success, partial completion or failure.
OUTCOME_UNKNOWN and RECONCILIATION_REQUIRED never authorize an automatic retry.

Qualified terminal attempts seal an immutable effect manifest and end only their
matching execution lease. Automatic settlement requires recorded complete-mediation
scope, current device trust at capture, closed authority and independently resolved
effects. A retained settlement remains historical evidence after later device
revocation; a later contradictory target receipt still requires reconciliation.
An operator-pinned OCI admission scope is not attestation or full phase acceptance.

GET /api/control/v1/access/invocations/{id} returns the raw invocation and its
derived outcome after checking current read authority. It does not supply execution
authority. Owner access is re-evaluated; other human readers need operation.read on
both the original Tool Group and Device Group. Management reads use current scoped user authority. Native caller reads use the
fresh Gateway context described below; reading retained metadata does not require
an unexpired execution deadline or reuse an obsolete execution credential.

GET /api/control/v1/access/invocations/{id}/history?limit=50 returns at most 64
attempts and 100 effect generations per page. Pass the returned next value as
cursor to continue. A cursor is pinned to the derived evidence revision; changed
evidence causes HTTP 409, requiring a fresh first page. Truncated/incomplete history
cannot prove finality. Attempt IDs, route IDs, evidence counts and digests are
metadata; the response excludes raw arguments, dispatch capabilities, credentials
and target claim tokens. A receipt digest identifies the authenticated target
claim; it is not a response-body digest.

Outcome grades distinguish REPORTED, CONTROL_PROVEN and TARGET_CONFIRMED.
A released authorization is Control proof of no dispatch, not target confirmation.
REVIEWER_CONFIRMED requires an approved independent human scope review.
TARGET_DEDUPLICATION_ASSUMED requires an independently approved guarantee source. Fixed-plan consent, generalized equivalence and provider
deduplication sources remain unavailable until their owning features are delivered.

Gateway/MCP places the current authorized Control outcome at
_meta["io.ololabs.toolgate/operationOutcome"]. Worker structuredContent remains
reported output and cannot replace this reserved metadata. RUNNING preserves the worker's reported error flag; FAILED, PARTIAL,
OUTCOME_UNKNOWN and RECONCILIATION_REQUIRED are tool errors. Unavailable outcome metadata returns an error with
OUTCOME_UNKNOWN and retryable=false, without disclosing the worker result.
No outcome/history endpoint renews a lease or grants a replacement permit.

The additive contracts retain the previous Java EnterpriseInvocation and
RemoteToolResponse constructors. Native human/local result responses also carry
the separate optional operationOutcome; their record state remains reported
transport state.
Older strict schema readers must update before consuming responses with the new
optional projection. V7 adds immutable manifests, settlements and outcome snapshots;
the separately staged future kill-switch specification moves to V8. Shipped
V1–V6 migrations and the preserved revision-10 plan remain unchanged.

## Native caller metadata

GET /access/invocations/{id} and GET /access/invocations/{id}/history on Gateway
authenticate the current agent/delegated caller or verified human caller. Gateway
forwards its fresh RequestContext to Control's gateway-role-only POST status and
history endpoints. Neither API accepts a caller-selected context in the public
request body. Control matches the original tenant, mode, user, agent, workload,
chain and binding, then checks current proof and grants. Unknown and unowned IDs
return the same 404. Rotating a valid credential does not rewrite the operation's
historical credential. These reads work after the original execution deadline
without admitting, reserving or renewing work. The history cursor and limit rules
above also apply; duplicate/unknown query keys and out-of-range limits are refused.

## Independent attempt-scope review

The console's effect-outcome inspection can propose an explicit no-further-effects
attestation only for consumed, sealed, inactive attempts with resolved generations.
The optional attemptSettlement binds expectedOutcomeRevision and exact attemptIds;
noFurtherEffects must be true. The existing external evidence digest is required.
Both maker and checker need current operation.approve over the original groups.
They must be different people and independent of both original and current caller/
agent owners, including super administrators. A transferred agent cannot make its
former owner independent. Legacy agent operations without an admission-time owner
snapshot cannot acquire this proof from current ownership.

New evidence invalidates approval (409); refresh history and propose against its
new revision. Stale proposals can still be denied. Approval atomically records the
review, scope proofs, derived outcome and audit; a requested state that disagrees
with derivation rolls everything back. Repeating approval does not duplicate proof.
This workflow does not resolve ambiguous/conflicting effects, invent receipts,
rewrite executor reports, clear a reconciliation latch or grant a new permit.
Legacy resource-only review does not implicitly attest complete attempt scope.

Derived PARTIAL also maps to a PARTIAL business-key outcome. Unknown/reconciliation
maps to OUTCOME_UNKNOWN. An unresolved or PARTIAL optional window cannot silently
rotate into another execution. V7 preserves existing key values, indexes and
retention guards while adding this distinct outcome on both database dialects.

## Conformance boundary

Run python tools/security/finalization.py for the source-bound Control component
vectors added to transitions, audit-spool and duplicate-execution. It creates its
own PostgreSQL fixture, reruns the selected Java classes and refuses missing,
failed, skipped, duplicate or source-stale evidence. Generated XML and a digest
report live under build/security/finalization. This does not accept the original
distributed suites or C1–C8 claims. Their unexecuted assertions remain visible in
the diagnostic scorecard. The authoritative status document explicitly amends
S10's stage boundary to this finalization feature and its qualified component
checks; full original distributed suites remain phase-1/S33 requirements. S10
earns no weight until the amended stage acceptance criteria are met. Prospective fixed-plan/equivalence/deduplication rules have policy tests,
but their live independent authority sources remain unavailable.
