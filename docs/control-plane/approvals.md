# ASK approvals and execution permits

Control evaluates current authority, stores approval obligations and human
decisions, and signs execution permits. Gateway routes the request and result;
it does not sign permits or authorize from a policy snapshot.

## Operation flow

1. An authenticated request reaches Control through its runtime access API.
   The current group grants, target binding, device trust and resource rules must
   permit the operation; an ASK obligation cannot repair a missing grant.
2. Reviewers inspect the exact pending operation. Human review uses
   `/api/control/v1/approvals` and `/{id}/decision`, with the canonical decision
   body and `Idempotency-Key`.
3. Review authority comes from current enabled directory membership, Management
   Roles and the policy's reviewer Teams. Scoped reviewers cannot approve their
   own operation. Super Admin has the explicit audited
   [self-review exception](../adr/022-super-administrator-review-exception.md).
4. Approval does not freeze permission. Control rechecks authority before permit
   reservation, online single-use consumption, protected effects and result access.
5. The executor verifies the exact permit binding and current execution gates.
   Expiry, revocation, changed arguments, stale authority and duplicate consumption
   fail closed. Unknown external outcomes require reconciliation, not blind retry.

Permits last at most ten seconds with zero grace. Device connection approval is
separate: an approved device still needs enabled identities, complete grants and
the exact operation approval. Unlimited connection approval never extends a permit.

## Diagnose an approval failure

Inspect the invocation, pending obligations, current reviewer authority, revision,
expiry and correlation ID. Re-read after a revision conflict. Confirm that the
requester, Agent, Tool and executor retain the same allowed relationship; an old
approval cannot survive a relevant revocation. Keep secrets and raw credentials
out of logs and support reports.

For fields and endpoints use the [Control OpenAPI](../../packages/contracts/openapi/control-v1.yaml)
and [access model](../security/enterprise-access-control.md). For operational state,
recovery and test requirements use [enterprise operations](../enterprise-access-control/operations.md)
and [testing](../testing/README.md).
