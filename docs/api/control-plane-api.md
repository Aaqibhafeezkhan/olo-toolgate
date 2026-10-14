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

## Purpose

Users, teams, tools, policies, approvals, clients, deployments, vault bindings, import/export and audit.

Module 02 exposes `/api/control/v1/{users,teams,agents,tools,policies,devices}`:
GET collection/item, POST create, PUT update and DELETE. POST requires revision 1;
PUT uses the current body revision and quoted `If-Match`, returning revision +1.
DELETE requires quoted `If-Match`. Deleted IDs are permanently retired per tenant
and kind. References must exist; enabled records cannot depend on disabled records.
Policies require at least one explicit subject and a declared tool action/resource
kind. Only ALLOW/BLOCK records are accepted; mutations alone do not distribute
runtime policy. Explicit signed publication uses the bundle endpoints below.

All mutations require `Idempotency-Key`, scoped to verified tenant and actor, with
seven-day retention. Identical request replay returns the original status/body/ETag
without another mutation or audit. Reusing a key for different content fails 409.
Authorization precedes replay. List `limit` is 1–100; `nextCursor` is absent at the
end. Directory cursors are bound to tenant/kind; no tenant can read another tenant's
rows by supplying a cursor, ID or snapshot.

`GET /config/export` returns a `ControlSnapshot` with a tenant revision ETag; Accept
may be `application/json` or `application/yaml`. `POST /config/import` accepts
`ControlImportRequest` in either format, requiring `If-Match` to equal both current
tenant revision and snapshot revision. `formatVersion: 2` only is supported; unknown
versions are rejected. MERGE overlays input records; REPLACE also deletes records of
exported kinds absent from the file, never users, memberships, identity bindings or
device evidence. Imported record revisions are normalized against
existing records. Dry-run validates the entire resulting graph and returns a stable
CREATE/UPDATE/DELETE diff without writes/audit/replay storage. Applying changes commits
atomically; tenant revision advances once, changed record revisions advance once.
An applied no-op import still receives an audit record and replay protection.

YAML uses safe construction, no custom object tags, duplicate keys or collection
aliases. Unknown fields, secret/code fields, invalid IDs, invalid references, ASK,
remote tool schema references, stale revisions and quota violations are rejected.
Tool schema objects are inert data; no network resolution or code execution occurs.
Credentials and vault secret values have no representation in directory contracts.
Do not embed credentials in descriptions, names or schema data.

`GET /audit` is admin-only, with a numeric sequence cursor and bounded limit.
Audit entries carry opaque SHA-256 actor IDs, operation/target, tenant revision,
request ID, timestamp and request digest, without payloads or credentials.
Each security mutation and applied import is recorded in its own transaction.

`GET /openapi` requires read access and serves a self-contained spec assembled from
the [canonical OpenAPI](../../packages/contracts/openapi/control-v1.yaml) and shared
schemas; it introduces no copied wire definitions. Errors use `ErrorEnvelope` and
`X-Request-ID`, without exception details. Configuration, JWT claims, roles and
management endpoints are documented in [configuration](../control-plane/configuration.md).
Early HTTP transport body/header limits can reject before the REST error envelope
and correlation filter; the canonical specification documents that boundary.

Module 04 adds `GET /bundles/current`, `GET /bundles/versions/{sequence}`,
`POST /bundles/publish` and `POST /bundles/rollback`, using the shared
`SignedPolicyBundle` and `BundlePublishRequest` models. Publication is admin-only,
idempotent and transactional, with expected directory revision/current sequence.
Rollback republishes historical policy as a higher sequence. The dedicated
`toolgate-bundle-reader` role can read current bundles only. See
[protocol, examples, grace and key rotation](../control-plane/policy-bundles.md).

Module 05 exposes `/api/control/v1/approvals`: bounded list/get, human `/{id}/decision`
with expected revision and Idempotency-Key, and separate machine `/resolve` and
`/permits/consume`. Dedicated IdP roles and signed approver user mapping are required;
admin alone does not grant approval. [Exact binding, states and errors](../control-plane/approvals.md).
