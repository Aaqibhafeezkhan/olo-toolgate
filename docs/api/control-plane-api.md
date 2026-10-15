# Control Plane API

The console consumes this canonical API on the same origin. Generated operation
metadata imports the shared TypeScript contracts. Device-management operations
are defined in the canonical OpenAPI and schemas, with no browser-owned security
decisions. See [console behavior](../control-plane/admin-ui.md).

## Device registry operations

`GET /api/control/v1/endpoint/devices` returns the bounded combined view of
registered directory devices, installed-client approvals, pending enrollments and
registered users. Quickstart enriches server-managed rows with availability.
`POST /endpoint/devices/{id}/approval` accepts `expectedApprovalRevision` and
`approved`, with a finite `connectionExpiresAtUnixMs` or explicit
`unlimitedConnection: true`. `POST /endpoint/devices/{id}/enabled` independently
accepts the directory `expectedRevision` and `enabled`. Both paths use the
`/api/control/v1` prefix, require admin authority and commit audit/idempotency
with the mutation. Approval revision is separate from heartbeat revision.

Enrollment review/decision requires an enabled same-tenant human enroller and
verified code/fingerprint. Pending metadata cannot issue a certificate. Permanent
`/endpoint/devices/{id}/revoke` retires the key and cannot be undone by reapproval.
See [the registry reference](../control-plane/device-registry.md) for complete
requests, expiry, legacy defaults and routing boundaries.

`POST /endpoint/enrollments/lookup` with `{"userCode"}` returns `{enrollmentId, deviceId,
platform, keyFingerprint, expiresAtUnixMs}` for a valid pending code the caller may approve
(group rule or `ownDevices`), and 404 without detail otherwise. Enrollment lists and device
reads return `userCode` and `keyFingerprint` only to callers holding `approve-device` through
a group rule. Five failed lookups or approvals per user in ten minutes return
`429 {"error":"RATE_LIMITED"}`.

## Administration and credentials

Settings (`GET/PATCH/PUT /settings`) are Super Admin only; a threshold change returns `202`
with a `SETTINGS` configuration change while reviews are required. `GET /review-policy`
returns `{"requiredReviews": n}` to every signed-in console user.
`POST /workload-bindings/{id}/credential` (Super Admin) takes `{"credentialSha256", "enable",
"bindingId"?}`, increments `credentialEpoch`, rebinds a delegated binding to its user's
current session epoch, clears `needsReauthorization` and returns
`{"binding": ControlWorkloadBinding, "context": RequestContext}` for the Gateway.
`POST /users/{id}/enable` and `/disable` (Super Admin) change a user and end its sessions
and delegated workload tokens. Refusals that name what to resolve use
`ControlErrorDetail`: `{"error": "UNRESOLVED_IDENTITIES"|"REPLACE_CONFLICTS"|
"ONE_DEVICE_PER_ACCOUNT"|"REQUIRED_REVIEWS_CHANGE"|"RATE_LIMITED", "ids": [...], "requestId"}`.
See [import/export](../control-plane/import-export.md).

## Canonical contracts

Use [control-v1.yaml](../../packages/contracts/openapi/control-v1.yaml) for exact
request/response fields and available operations. Current directory collections
include group grants, bindings, delegations, roles and verified identity records.
Management permission comes from enabled directory membership, not token roles.

Runtime service calls use `/api/control/v1/access` for invocation submission,
reservation, permit consumption, reports and authorized results. Executor calls
require their actual certificate-bound identity. See [approvals](../control-plane/approvals.md),
[device registry](../control-plane/device-registry.md) and [Tool Host](../../apps/tool-host/README.md).

Mutations use the operation's canonical revision and idempotency contract. Stale
revisions require reloading; replay cannot broaden scope or duplicate effects.
See [errors and idempotency](errors-and-idempotency.md).
