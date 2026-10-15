# ADR 006: Durable exact-operation approval and single-use permits

> Historical decision. Runtime authority and permit-signing details are superseded
> by [Control online authorization](../architecture/overview.md) and
> [ADR 013](013-tool-host-egress-broker-secrets.md). This is not a current deployment guide.

Status: accepted for Module 05. Date: 2026-10-03.

Control owns human decisions and durable request/permit consumption. Gateway
owns runtime authorization, normalized input, current-policy checks and permit
signing. Existing PostgreSQL supplies atomic concurrency; no new infrastructure
or mandatory notification provider is introduced.

Signed policy format 2 adds ASK through new canonical types. Format 1 schemas and
frozen fixtures remain unchanged. Gateway reads both formats; older readers reject
format 2 safely. BLOCK > ASK > ALLOW. ASK never uses expired-policy grace.
The approver population is explicitly the IdP's tenant-scoped `toolgate-approver`
role. Approval also requires a signed `user_id` claim referring to an enabled
directory user; a requester cannot approve their own request. Admin alone does
not imply approver. Machine calls require `toolgate-approval-gateway` and cannot
decide requests. Browser submissions cannot create runtime approval requests.

An exact binding covers tenant/user/agent/device (including absence), tool/action,
extracted resource, arguments digest and policy version. Changing ingress request
IDs does not broaden a temporary grant. Each permit binds its own exact request ID
as well. Control independently requires a matching ASK in the current published
policy, with BLOCK precedence and fresh validity. Directory identities must remain
enabled. Publication invalidates earlier-version approvals.

State transitions are PENDING -> APPROVED_ONCE | APPROVED_TEMPORARY | DENIED |
EXPIRED; APPROVED_ONCE -> CONSUMED | EXPIRED; APPROVED_TEMPORARY -> EXPIRED.
Consumed/denied states are tombstones until their deadline; a new request ID
cannot recreate a grant during that window. Decisions use expected revisions.
Resolve/create, decisions, expiry and permit consumption use tenant-serialized
database transactions with audit and durable idempotency. Pending requests expire
within five minutes; temporary approvals are capped at thirty minutes and policy
expiry. Pages, active requests, stored input and total HTTP time/size are bounded.
Lazy expiry is durable and audited, with explicit status returned by the API.

Gateway calls a fixed, administrator-configured HTTPS Control origin (literal
loopback HTTP only when explicitly enabled for development), without redirects
or proxy inheritance, using an externally refreshed machine JWT. No grant cache
is allowed. Any approval fetch, validation, signing or audit failure blocks ASK.
Policy is re-evaluated after remote coordination and after audit. Approved ASK
does not override BLOCK, version changes or policy expiry.

Permits use compact JWS RS256 with protected type
`toolgate-execution-permit+jws`, a dedicated external Gateway private key and
issuer/audience configuration. Trust is separate from policy/IdP/deployment and
Marketplace keys. Claims bind identity, device, operation/resource/arguments,
request ID, policy version, approval ID, jti and a maximum ten-second lifetime
capped by approval and policy validity. Gateway verifies exact bytes and binding
before the separate consume endpoint atomically spends jti in Control. Signing
alone does not make a permit single-use. No endpoint execution is implemented in
Module 05; later clients must enforce this consume boundary.

The new `/v2/authorize` returns a canonical decision/approval reference/permit
outcome. Existing `/v1/authorize` and MCP decisions remain compatible and fail
closed on ASK rather than losing a permit/reference. `/v1/permits/consume` verifies
and atomically consumes an exact-bound permit. No connection is held while a
human decides. The requester retries a new authorization attempt after approval.

Notifications are an optional port. Absence never affects correctness; durable
UI polling remains the primary channel. Raw arguments and credentials never enter
approval records, comments, audit exports, metrics or notification payloads.
Signing secrets remain external; Helm remains opt-in with explicit egress and
rollout revisions. Rollback preserves approval history and cannot resurrect a
spent permit or authorize through obsolete policy.

Compatibility review: `ControlPolicy.decision` already references the shared
Decision enum (including ASK), but Modules 02–04 constrained administrative schema
validation to ALLOW/BLOCK. Module 05 explicitly widens that guard. Existing model
shapes/imports remain identical. Earlier administrative schema readers reject ASK;
older runtime readers reject signed format 2. Upgrade both services before enabling
ASK. This security-sensitive change is explicit in the 0.5.0 minor release.
