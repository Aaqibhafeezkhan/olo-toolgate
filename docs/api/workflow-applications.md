<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Workflow integration for external applications

V1 uses an existing enabled OLO user's username and password. OAuth, refresh
tokens and delegated application credentials are deferred. The password endpoint
below belongs to the Quickstart composition; standalone Control does not host a
password verifier. Use a build containing this implementation. Source tests do
not establish availability in previously published images or production readiness.

## User and resource setup

An installation operator reviews, prepares and publishes the bot and configures
its model custody, packages, devices, bindings and runtime service grants. Choose
the actual OLO users allowed to execute it. An enabled BASIC user can call these
APIs through enabled Team membership and explicit HUMAN grants, directly or
through a HUMAN runtime role. A current enabled tenant Super Admin receives
tenant-wide human runtime authority from Control without per-bot HUMAN grants.
Token role claims cannot confer it, and independent runtime service/effect
authority and policy obligations remain required.

The [all-bot permission matrix](../../bot/PERMISSIONS.md) lists exact workflow,
agent and model IDs, declared tool/device groups, packages and file requirements
for every supplied bot. Grant `workflow.execute`, `agent.execute` and `model.infer`
on the listed CUSTOM/EXACT locators, plus human coverage of every bound agent's
declared SERVICE/CAPABILITY/SECRET ceiling. One human scope must cover each
required scope. Partial scopes do not combine into a larger cross-product grant.
Admission cannot satisfy online, network, posture, high-risk, amount or quota
evidence conditions. Runtime service checks and effect policy remain independent.

Observation requires `workflow.progress.read` and/or `workflow.result.read` on
the workflow and current resource grants. Add `workflow.subscribe` for live
streams and callbacks. Ordinary manual run reads are owner-scoped; a current
tenant Super Admin may inspect any run in that tenant. A user's new login may
read their older run with current observation/resource grants; it does not revive
the execution identity invalidated by a session or password revocation.

## Login, discover and submit

Send exactly one `Toolgate-Tenant-ID` header containing the console's tenant UUID
to `POST /api/public/v1/auth/login`, with JSON:

```json
{"username":"your-olo-user","password":"your-private-password"}
```

For the example installation the UUID is
`48558ebe-a9a8-4680-8da8-129deba2e65c`; replace it for another installation.
Missing, duplicate, malformed and mismatched login tenant headers are rejected.
The response has `accessToken`, `tokenType: "Bearer"` and `expiresIn: 900`.
Subsequent calls use `Authorization: Bearer <accessToken>`. The verified token's
`tenant_id` supplies tenant scope; an optional tenant header must agree. Login
does not register users, enable disabled identities or provision missing passwords.

Use loopback HTTP only for local evaluation. Remote access requires reviewed
verified HTTPS ingress. Quickstart restricts Host/Origin to its local composition;
a trusted backend ingress must route with the expected local Host. This is not a
generic browser CORS integration. Keep passwords, tokens and private responses
out of URLs, exported collections and logs.

1. `GET /api/control/v1/workflow-profiles/published?limit=50` lists accessible
   publications. Copy `profileId`, `digest` and `generation`.
2. `GET /api/control/v1/workflow-profiles/published/{profileId}/{digest}` returns
   the redacted catalog. Choose a workflow ID and entry-eligible agent ID.
3. `POST /api/control/v1/workflow-profiles/{profileId}/workflows/{workflowId}/runs`
   with `Idempotency-Key` and JSON:

   ```json
   {"profileDigest":"<digest>","expectedGeneration":1,"entryAgentId":"<agent-id>","message":"<bot input>"}
   ```

Supply `dispatchBindingId` when routes require disambiguation. A `201` receipt
means admission, not completion. Keep the same key and identical body after a
lost response. A new key can cause new effects. Current authority is checked
before replaying a receipt and throughout execution; no transport retries
uncertain workflow effects.

`GET /api/control/v1/workflow-runs/{runId}` supplies state and private outcome.
`GET /api/control/v1/workflow-runs/{runId}/progress?after=0` supplies structural
progress and `nextCursor`. Poll about once per second. Approval, failure,
cancellation and expiry are not success. These APIs do not stream model tokens.

## Durable events and live streams

Use `GET /api/control/v1/application-events/WORKFLOW/{runId}?after=0` for durable
`PROGRESS,RESULT` notifications. Add `/stream` and `Accept: text/event-stream`
for SSE; reconnect with the last numeric `Last-Event-ID`. Both require
subscription authority for streams, with selected read authority at every poll
and delivery. Tokens expire; reconnect after a fresh login. Streams last at most
60 seconds, with limits of 16 per node and two per tenant/user.

For trigger status, use
`GET /api/control/v1/application-trigger-status/{assignmentName}` and
`GET /api/control/v1/application-events/TRIGGER/{assignmentName}/stream`.
Grant `trigger.read` on `trigger-assignment:<actual-assignment-UID>` and its
target Device Groups; add `trigger.subscribe` for subscriptions. Status contains
no provider payload/configuration and conveys no workflow execution permission.

Event pages contain at most 200 records. Each source retains 4096 records;
nonzero cursors preceding retention fail with `409`. Cursor zero starts at the
retained history. Result notifications announce an authorized result path;
fetch the private result separately with current Bearer authority. Events are
observations, not proof of effect success or permission to repeat work.

## Signed HTTPS callbacks

An operator sets `TOOLGATE_APPLICATIONS_WEBHOOKS_ALLOWED_HOSTS` to approved exact
DNS hostnames in installation configuration and restarts the service. Without
an allowlist callbacks are unavailable. Endpoints must use HTTPS on port 443,
without userinfo, query or fragment. Private, loopback, metadata and special-use
destinations, mixed public/private DNS answers, proxies and redirects are refused.
Connections pin a validated address while verifying the original TLS hostname.

Create with `POST /api/control/v1/application-webhooks`, an `Idempotency-Key`,
and JSON matching this shape:

```json
{"sourceType":"WORKFLOW","sourceId":"<run-id>","eventKinds":["PROGRESS","RESULT"],"callbackUrl":"https://your-approved-host/events","expiresAtUnixMs":1800000000000,"after":0}
```

Replace the illustrative expiry with an epoch-millisecond value after now and
no later than the login token's expiry. Registration returns subscription
metadata and a private base64url `signingSecret`. Store the secret securely. Identical
authenticated create retries recover it; metadata/list APIs never return it.
Start in `PENDING`, configure the receiver, then
`POST /api/control/v1/application-webhooks/{id}/verify` with `{}`.

Requests carry `Toolgate-Event-ID`, `Toolgate-Event-Timestamp` (epoch seconds)
and `Toolgate-Event-Signature: sha256=<hex>`. Verify HMAC-SHA256 using the decoded
secret over `timestamp + "." + rawRequestBody`, in constant time, and enforce a
fresh timestamp. Ownership verification sends `type: "VERIFY"`, `subscriptionId`
and `challenge`; return JSON `{"proof":"<hex>"}` where proof is HMAC-SHA256 over
`"verify." + subscriptionId + "." + challenge`. Return a bounded JSON response
with Content-Length (at most 4096 bytes); chunked proof responses are unsupported.

Active deliveries contain `subscriptionId` and an `event` with tenant identity.
Check subscription, tenant and event ID; durably deduplicate before returning
2xx. The same event ID is retried after lost acknowledgements. Retries use bounded
backoff and fail after eight failed attempts. A crash after send can redeliver;
there is no exactly-once business effect guarantee. Current source grants,
identity state and expiry are checked before outbound delivery.

`GET /api/control/v1/application-webhooks` lists the caller's subscriptions;
current tenant Super Admins can list, inspect, verify and revoke any registration
in their tenant. Metadata never returns another registration's signing key.
Administrative verification still requires the recipient's proof and the original
subscriber's current source authority, identity and expiry; it cannot resurrect
a revoked session or subscription. `GET /{id}` reads state/cursor;
`DELETE /{id}` revokes. For renewal or key recovery,
login again, create/verify a replacement using the last acknowledged cursor,
then revoke the old registration. Retention gaps require reconciliation. Password
changes and `POST /api/control/v1/application-session/revoke` with `{}` invalidate
the corresponding login sessions and their execution/subscription identities.

Use the [Postman collection and receiver example](../../deploy/docker/quickstart/dev/externa-workflow-integration/postman/README.md).
A chat adapter must additionally verify its provider's authentication/replay rules,
map trusted identities to authorized OLO users/bots and persist provider-event to
idempotency-key/run mappings. OLO does not supply provider-specific chat adapters.

## Verification limits

Local database, transport and proxy tests do not establish full-stack bot
completion, external provider integration, published-image readiness or HA
qualification. Remaining acceptance is tracked in the
[integration qualification plan](../plans/workflow-application-integration.md).
