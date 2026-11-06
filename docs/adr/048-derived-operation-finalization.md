<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 048: Derived operation finalization and caller history

**Status:** Accepted; bounded S10 implemented and qualified under the explicit
stage scope amendment in the authoritative execution status. Full phase/security
acceptance remains outstanding.

## Decision

Derive the business outcome from the complete union of every attempt and every
effect generation. Preserve the legacy invocation state as executor-reported
state, not independent evidence of business completion. One old report never
overwrites an operation outcome, a replacement dispatch or another attempt.

Implement the revision-10 ordering: witnessed conflict or an extra completed
effect requires reconciliation; active work is RUNNING; unresolved effects or
unsettled attempts are OUTCOME_UNKNOWN; only closed and resolved history can
become SUCCEEDED, PARTIAL or FAILED. Missing, legacy, malformed or truncated
history cannot prove success. Current authorization remains necessary for
caller history; an outcome/history read never supplies execution authority.

Each consumed attempt has an append-only manifest that grows with authorization.
A terminal report seals it atomically with refusal of further authorizations.
Unknown attempts need a recorded settlement, a closed manifest/authority and
positive complete-mediation scope or independent human reconciliation. A sealed
fixed plan may establish success after a worker crash only when all its nonempty
slots are witnessed complete; read-only empty manifests still need a successful
attempt. Unimplemented consent/fixed-plan and target-guarantee sources remain
unavailable, not inferred from worker claims or tool labels.

Equivalent effects require independent witnessed reconciliation and closed
authority. Equivalence cycles, missing references or unresolved authority are not
resolution. Different witnessed receipts for one effect require reconciliation.
A response digest alone is not a business receipt. Repeated success merges only
with the same witnessed target receipt or an independently reviewed target
deduplication guarantee; any guarantee assumption stays explicit in the grade.

Keep immutable finalization/manifest/settlement evidence and derived revisions
under tenant writer transactions with audit. A contradiction can move a final
outcome only to RECONCILIATION_REQUIRED. ADR 047's explicit proven-no-write
recovery is the narrow exception allowing FAILED to become RUNNING for a new
attempt; an arbitrary late report cannot make that transition.

Add canonical caller contracts before bindings, persist migration changes in new
versions, and integrate the derived view into Control, Gateway/MCP and human/
console callers. Unknown outcomes are not retryable. Preserve cancellation,
ownership, deadlines, current authority and independently graded reported versus
witnessed outcomes. Expose bounded history without raw arguments, credentials,
target claim tokens or archived dispatch capability bytes.

## Delivery and verification

Start with focused derivation rules covering all original transition examples,
including old ambiguous effects, late failures, extra writes, read-only crashes,
matching/conflicting receipts, approved assumed deduplication, equivalence and
active work. Then integrate authoritative SQL, manifest closure/settlement,
late-evidence derivation and caller behavior incrementally on SQLite/PostgreSQL.
Execute the source-bound finalization component vectors in transitions,
audit-spool and duplicate-execution, applicable component gates and denied-effect
checks before one complete S10 commit with progress. The authoritative status
document records an explicit S10 baseline scope amendment: full original
distributed suites remain mandatory phase-1/S33 exits; later live consent,
equivalence and deduplication authorities retain their owning-stage requirements.
Until the amended stage criteria pass, S10 stays Partial with no additional
weight. Full roadmap-phase acceptance is separate.


## Late executor report qualification

Capture immutable report qualification at receipt from the existing consumed
attempt, independently captured broker-only scope and current endpoint/directory
trust, under the same transaction. Historical reports default to unqualified;
worker fields and later device re-enablement cannot upgrade them. A qualified
successful complete result can contribute an attempt's reported success after
its authority closes, but never resolves a pending effect, bypasses missing
scope or changes a final business outcome except through reconciliation.
Keep the legacy invocation/current-dispatch pointer untouched. Bound report
parsing and disclose incomplete history; malformed or unqualified reports remain
reported evidence, never positive completion proof.


## Independent human attempt-scope attestation

Extend the existing maker/checker reconciliation with an optional exact set of
consumed attempts and expected derived evidence revision. Both people must have
current scoped review authority, be distinct and independent of the operation's
user and agent owners; this path has no owner/super-admin independence exception.
They attest with the existing external evidence digest that the listed attempts
had no effects outside their closed, resolved ledgers. Capture the approved
proposal and evidence head in immutable attempt-review records. The review never
rewrites executor state, invents a target receipt or reopens authority. It cannot
resolve a pending/conflicting generation or clear a final reconciliation latch.
Existing resource-only reconciliation remains a separate legacy report workflow
and cannot supply this stronger scope proof implicitly.


Capture the original caller/agent-owner set at new operation admission in an
immutable tenant record. Human scope review excludes both original and current
owners. A legacy direct-human operation can use its immutable caller user ID;
legacy agent operations without an original owner snapshot cannot gain this proof
retroactively from current ownership alone.


## Native caller reads

Provide Gateway status/history reads using a newly verified caller context, not
the historical execution credential. Control binds mode, user, agent, workload,
chain and binding to the original operation, rechecks current directory credential
proof and grants, and exposes metadata only. Reads never renew historical context,
resend, reserve or extend execution deadlines. Unknown/unowned IDs are uniformly
not found. A current caller can inspect retained metadata after an execution
deadline; raw result access retains its separate authorization rules.
