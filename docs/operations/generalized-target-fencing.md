<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Generic HTTP target fencing

S21 supports explicit generic HTTPS, HTTP/1.1, non-streaming targets using Java 21
and the shared JDBC claim store. Deploy matching current artifacts and fresh
schemas. There are no upgrade chains, backfills, format conversion, compatibility
constructors or legacy runtime paths. An unsupported store is refused intact.

## Declare and review the target

In the package route review, select the target implementation and its identity:
- SHARED_SQL: every replica shares the database containing both business data and
  claims. Apply, business mutation, completion and outcome intent can be one SQL
  transaction.
- SQL_WITH_EXTERNAL_ACTION: every replica shares SQL claim ownership, but an
  external action is outside its transaction. Commit APPLYING before external
  I/O. A crash or uncertain provider response leaves APPLYING unresolved.

A current human with package.deploy authority reviews the exact route intent,
targetId and targetClass. Both issuance and first consumption recheck the current
reviewer's authority. Package/worker declarations cannot stamp this review.
The target ID is lower-case and matches [a-z][a-z0-9-]{0,63}. Fenced reads,
streaming routes and simultaneous unfenced-risk/dedupe declarations are refused.
Use CA-verified HTTPS for this mTLS path; leaf-pin-only profiles are unsupported.
Exact consent remains mandatory for FINANCIAL and DESTRUCTIVE effects.

Provision a separate trusted machine credential with the exact role
toolgate-target-{targetId}, scoped to the target's tenant. It is not an executor,
agent or human credential. The target endpoints live under
/api/control/v1/access/target-permits/{targetId}/. They expose jwks,
claims/consume, claims/status and claims/outcome; none is a generic caller write
API. A role for one target cannot consume or settle another target's effects.

The broker injects Toolgate-Effect-Permit only after freezing the exact request.
It is absent from worker authority and excluded from the business fingerprint
only by the target verifier after checking its unique exact value. Caller-supplied
permit headers are refused, including attempts to declare them as credentials.
The broker presents the executor mTLS key bound into the signed grant. Unfenced
targets continue to receive no supervisor workload identity.

## Integrate the target

Use ControlTargetAuthorization with an HTTPS Control origin, a redirect-disabled
HttpClient, trusted issuer/tenant/target configuration and a private credential
supplier. Construct ServicePermitVerifier.Transport from trusted public routing
configuration and actual accepted URI/method/repeated headers, never a worker
manifest. Pass the exact buffered body and the accepted listener SSLSession.

1. Verify the signed permit against those accepted facts. VerifiedWrite is
   evidence, not permission to mutate or commit.
2. In a caller-owned target transaction, acquire JdbcEffectClaims.claim using
   the verified binding and original expiresAt, then commit the claim. Only the
   insertion winner receives an owner token. A losing replica returns status
   and applies nothing.
3. Call consumeClaim with that verified write and owner. Revocation committed
   before first consumption refuses it. A repeat returns status; it never
   grants a different owner or extends the original deadline.
4. Win beginApply with that owner before acting. For SHARED_SQL, mutation and
   finish may share that transaction. For external actions, commit APPLYING
   first. A paused owner whose CLAIMED state was abandoned cannot win this CAS.
5. Check the returned commit deadline immediately before committing the business
   transaction. The grant retains its original two-second wall/monotonic bound.
   Never retry the business effect after an uncertain send, lost reply or crash.

Initialize JdbcEffectClaims once in a fresh target schema with schema-owner
credentials. All replicas must use the same durable store; retain claims and
outcome tombstones. Use least-privilege target runtime SQL access and preserve
tenant isolation. The target must enforce its own native authentication and
business ACLs too; ToolGate does not replace them.

## Recover evidence without repeating actions

An expired CLAIMED record may be abandoned by CAS, advancing ownership generation
and atomically enqueueing a fixed not-started witness. Only after that transaction
commits may its pending FAILED outcome be sent. Control accepts this exact
negative witness only for the bound target and an unconsumed generation; it does
not consume a permit or reopen dispatch. A consumed owner abandoned before
APPLYING can likewise report its durable not-started evidence.

APPLYING must never be abandoned as not-started. A timeout, missing provider row,
HTTP success or executor report is not conclusive target state. Keep uncertain
work unresolved. After independently verifying a positive target-owned business
receipt, confirmApplied may CAS the observed APPLYING snapshot to DONE and
enqueue its result digest in the same transaction. This method records evidence
only, returns no owner and sends no business request. A competing completion
cannot be overwritten. A separately verified closed target failure that permanently rules out later apply
may use confirmFailed. This rejects the not-started witness; a timeout, missing
row, executor error or uncertain provider reply remains unresolved.

Deliver pending outcomes with reportOutcome, then acknowledge accepted evidence
in a target transaction. Lost acknowledgement retries only evidence reporting.
Use bounded keyset pages so deferred records cannot starve later outcomes.
Status and evidence remain available after permit expiry, without new execution
authority. Conflicting target evidence fences the generation as CONFLICT.

## Qualification boundary

The shared claim adapter provides unique ownership and crash recovery for its
declared integration; an operator declaration alone is not conformance evidence.
Control and broker refusal, real accepted mTLS/HTTP facts, both target classes,
SQLite/PostgreSQL runtime roles, competing owners, paused-owner takeover,
apply/recovery races, lost replies, external-action crashes and outcome delivery
are tracked in the authoritative
[execution status](../security/zero-trust-execution-status.md).

This does not qualify arbitrary target frameworks, native MCP business finality,
other runtime tiers, production connectivity SLOs or the full original phase-3
distributed/kill/partition catalog. Those boundaries remain explicit.
