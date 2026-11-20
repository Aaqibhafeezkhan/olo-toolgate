<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# External application workflow execution

Status: proposed implementation, not an available API. This is unfinished scope
for [workflow execution](workflows.md). The current
[Quickstart API recipe](../../deploy/docker/quickstart/dev/README.md#invoke-a-bot-from-another-application)
documents the existing operator-only endpoints.

## Required behavior

The corresponding user must already exist and be enabled in the OLO tenant, with
the required role membership and permissions. Authentication is mandatory. An
external application's token must resolve to that OLO user; possession of an
application credential alone must not create a user or execution authority.

Any such user, including a user without an administrative role, can invoke a
published bot when they have an explicit execution grant for that bot and current
grants for all required resources. Administration, visibility,
device approval and bot import do not grant execution. Super Admin does not bypass
runtime resource grants.

An external application must be able to submit input, receive a stable run identity,
read its authorized progress and final response, open a resumable live event stream,
and register HTTPS webhook delivery of progress and result notifications. Both
streams and webhooks are required; polling remains a recovery path. These transports
observe the same governed run and cannot independently admit or retry work.

## Authenticate first, then call or subscribe

V1 uses OLO username/password authentication. OAuth/OIDC federation, authorization
code/PKCE, delegated application credentials and OAuth grant flows are deferred to
a later version and are not prerequisites for V1 delivery. This is a direct OLO
login endpoint, not the OAuth resource-owner password grant.

Supply the tenant UUID displayed in the console when logging in to OLO, using
exactly one `Toolgate-Tenant-ID` header, with `username` and `password` in the JSON
request body over verified HTTPS for remote access. Validate those credentials
against the existing enabled OLO user in that tenant. After authentication, the
issued signed access token must contain that UUID in its `tenant_id` claim,
alongside the verified user identity. Subsequent calls use
`Authorization: Bearer <accessToken>`; a separate tenant header is not required
for discovery, bot submission, progress/result reads, stream opening/reconnection
or subscription management. Do not send the password with bot or observation
requests, or place it in a URL, event payload or callback registration.

For the user's example installation, the login header is:

```http
Toolgate-Tenant-ID: 48558ebe-a9a8-4680-8da8-129deba2e65c
```

The console's `Quickstart` label is a display name, not an API tenant identifier.
Do not hard-code this example UUID as a default for other installations. At login,
reject missing, malformed, duplicate or mismatched tenant headers before
tenant-specific identity operations or token issuance. Validate agreement between
the supplied UUID, host-routed tenant and authenticated OLO user. After login,
derive caller tenant scope from the verified token claim and check it against the
routed tenant. Reject absent/invalid token tenant claims and cross-tenant requests.
If a client also sends a tenant header, it must agree with the token and route;
it cannot override them. Switching tenants requires authentication/token issuance
for the other tenant, not editing a request header or an unsigned token payload.

Quickstart already issues a signed `tenant_id` claim, and Control checks the
verified principal tenant against the routed tenant. The Quickstart-local login
does not currently validate the supplied tenant header. Implement and qualify
mandatory tenant selection/validation at login while preserving token-based tenant
binding after login. In V1, acquire a new token by logging in again when it expires;
do not assume a refresh-token endpoint. A new login must validate tenant membership
again. Keep internal service and device identity protocols
within their separately defined contracts.

The required integration sequence is:

```text
Third-party application -> OLO username/password login + tenant header
                        <- short-lived access token bound to that user and tenant
                        -> bearer-authenticated request/subscription (tenant in token)
OLO                     -> current role/resource permission checks
                        <- run receipt, permitted status/events or final response
```

Use OLO's password-verification and session infrastructure, including protected
password storage, bounded login attempts and generic authentication failures.
Specify the V1 login endpoint and request/response in the canonical API before
implementation; the existing Quickstart recipe documents only its current local
login. Keep passwords and tokens out of logs and shared Postman exports. Every
issued token must identify the authenticated OLO user and tenant. A service-only
identity or shared administrator account must not replace the corresponding user.

Validate token signature, issuer, audience, tenant, expiry and current user/session
status. Resolve current enabled team/role membership and grants from OLO rather
than accepting client-supplied role names, user IDs or token scopes as authority.
Define bounded token lifetime and revocation behavior. Expired/revoked
tokens require username/password reauthentication; they
cannot preserve a subscription's authority indefinitely.

| Operation | Required authorization |
|---|---|
| Discover a bot or resource | Current permission to view that specific tenant resource |
| Submit a bot request | Explicit workflow execution permission and required agent/model/tool/device/resource/secret permissions throughout the selected flow |
| Read workflow progress or a result | Current run ownership or explicitly granted run access, plus the applicable observation/resource permissions |
| Subscribe to trigger events or in-progress trigger status | Current permission for the selected trigger assignment/source and its scoped resources, with event/status read and subscription permission |
| Register a stream or webhook | The same user must be allowed to observe every selected run/trigger and event type; registration itself grants no additional access |

Treat workflow execution progress and trigger lifecycle/status as separate resource
types. A trigger subscription does not authorize starting its workflow, viewing
another user's run or receiving a private result. Execution permission does not
automatically grant subscription or result access. Return only records, fields and
event types allowed to the authenticated user through their current roles/grants;
never offer a tenant-wide feed merely because the token is valid.

## Authorization and ownership

- Specify the canonical bot execution action and exact bot locator in contracts.
  Resolve human grants from live enabled team/role membership, independently of
  portal management permissions. Define the required resource closure for supervisor,
  every reachable agent, model fallback, context tool, dynamically selected tool,
  device, resource and secret. Do not infer it from a display-only profile.
- Pin the initiating tenant/user/application and admitted dependency ceiling to the
  durable run. Check current authority before idempotency replay, worker claim,
  agent/model/tool use, result reads and event delivery. Permission changes must
  not let an existing run acquire a broader ceiling. Preserve existing service,
  capability, delegation, policy and effect checks as additional requirements.
- Define explicit run ownership/read/subscription rights. Knowledge of a run ID or
  permission to execute the same bot must not reveal another user's input/output.
  Bot lists and configuration discovery must be filtered without disclosing hidden
  profiles or resources. V1 uses the verified identity from that user's password
  login; never a shared administrator token for untrusted chat users. Delegated
  application identities are deferred with OAuth support.
- Remove management-session and Super Admin restrictions only after the replacement
  checks exist on submission, discovery, progress, result and subscription paths.
  Registering a callback cannot grant run or tenant access.

## HTTP contracts and routing

Keep Control as the authorization and durable run owner. Decide whether the Rust
Gateway exposes a public facade; if it does, preserve verified caller identity and
tenant, bounded requests and Control decisions. Its existing
`/v1/chat/completions` is model inference for an admitted run, not bot submission.
Update canonical schemas, OpenAPI, generated clients and proxy tests together.

Admission returns a run ID without waiting for completion. A stable idempotency key
and identical request identify one submission; conflicting reuse is rejected.
Retries of observation/delivery must never replay a workflow or uncertain effect.

Define bounded, versioned event envelopes with stable event IDs, run identity,
sequence/cursor, event kind, timestamp and minimal authorized payload. Progress and
terminal result are distinct event kinds. Define cursor retention/expiry, event
ordering, pagination, duplicate handling and terminal states, including approval,
cancellation, failure and expiry. Private messages, provider credentials, lease
tokens and tool secrets must not leak through structural progress.

## Live streams

Provide authenticated Server-Sent Events over HTTP with cursor resume, heartbeats,
disconnect cleanup and bounded buffering/concurrency. Recheck expiry and revocation
during a connection and stop unauthorized delivery. Do not put bearer tokens in
URLs. Reconnect using the last acknowledged event cursor without starting a new run.
Qualify streaming through the Quickstart proxy and reviewed external ingress;
the existing buffered JSON proxy is not evidence of stream support.

## Webhook registration and delivery

Provide authenticated create/read/revoke operations for caller-owned subscriptions,
with explicit run/bot or trigger/status and event-type scope. Persist the owning
OLO tenant/user/application and re-evaluate that user's current role/resource
permissions at delivery. Do not persist their bearer token as delivery authority.
Callback signature verification authenticates OLO to the recipient; it does not
replace the subscriber's OLO authorization. Require verified HTTPS destinations,
ownership verification and reviewed egress policy. Reject credentials in URLs,
redirects and disallowed loopback/private/link-local/metadata destinations; prevent
DNS rebinding at connection time. Never forward the caller's bearer token.

Use per-subscription signing custody, bounded signed envelopes, delivery IDs,
timestamps and documented verification/key rotation. Keep private signing material
in installation custody. Do not expose it in profile bundles, events or logs.

Persist subscriptions, outbox events, delivery attempts and acknowledgements in
tenant-scoped fresh schemas. Commit event creation with the state transition it
describes. Use replica-safe leased delivery, bounded exponential backoff, timeout,
retry budget and dead-letter/recovery behavior. Recheck current permissions before
sending. Revocation stops future sends; already delivered data cannot be recalled.
At-least-once delivery requires recipient deduplication by event ID. A callback ACK
acknowledges notification delivery only, never authorizes or repeats a tool effect.

## Acceptance before documenting availability

- Execute an ordinary non-admin user's text bot through public ingress and obtain
  its real final response. Deny missing bot/resource grants, disabled users/teams/
  roles, expired grants, wrong tenants, substituted identities and guessed run IDs.
- Exercise token acquisition for an existing enabled OLO user. Deny unknown users,
  service-token substitution, invalid issuer/audience/signature, expired/revoked
  sessions and client-supplied identity/role escalation. A valid token with missing
  workflow, agent, tool, device or other required permissions must still be denied.
- Qualify V1 username/password login: correct and incorrect credentials, disabled
  users, tenant membership, bounded failed attempts, protected password storage,
  password/session revocation, token expiry and reauthentication. No OAuth service
  or refresh-token flow may be required for V1 acceptance.
- Exercise mandatory tenant selection at OLO login; deny omitted, malformed,
  duplicate and wrong-tenant headers before identity writes or token issuance.
  Prove discovery, admission, polling, stream reconnect and subscription CRUD work
  with the tenant-bound bearer token alone. Deny missing/forged token tenant
  claims, route mismatches and conflicting optional tenant headers before writes
  or private data disclosure; obtaining a new token must revalidate its tenant.
- Verify trigger-status/event subscriptions are filtered by the user's current
  role and resource access. Cover unauthorized sources, mixed authorized/forbidden
  selectors, cursor substitution and private fields; prove that status observation
  cannot grant execution or another user's result access.
- Prove revocation between submission, claim, protected steps, result access and
  event delivery. Cover discovery, replay and concurrent permission changes.
- Exercise SSE reconnect/cursor replay, terminal delivery, token expiry, revocation,
  slow consumers, disconnects and actual proxy streaming.
- Exercise verified webhook registration, signature verification, destination
  rejection/DNS rebinding/redirects, duplicates, failed ACKs, backoff exhaustion,
  revocation, restart and multiple delivery workers without duplicate execution.
- Run SQLite and PostgreSQL persistence/tenant negatives, contract regeneration,
  Java/Rust/proxy component gates and the applicable workflow end-to-end scenarios.
  Use fresh schemas; do not add migrations or old-format conversion.
- Publish matching tested images, then add executable Postman, Rust caller, SSE
  client and signed webhook receiver examples. Record checks actually executed.

No implementation or runtime acceptance is claimed by this document.
