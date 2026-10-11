<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 015: Credential classification, catalog scope and MCP routes

**Status:** Accepted (design gate D1). Amended in M1: device placement, see [Amendment](#amendment-m1-device-placement).

## Context

The Gateway accepts only opaque bearer tokens today: up to 256 mounted SHA-256 hashes, each mapped to a fixed `RequestContext` that requires `deviceId` and `bindingId`, for a single tenant, on a single `/mcp` route (plan.md §2.1). Server-side agents have no device, enterprise tenants need IdP-issued JWTs and introspected tokens, and Rahul asked for agent and agent-group URL routes. Control already models `ControlWorkloadBinding` (with `credentialEpoch`, `expiresAtUnixMs`, `delegatedSessionEpoch`), `ControlIdentityBinding`, `ControlAgentGroup` and `ControlAgentDelegation`.

[plan.md](../sdk-plan/plan.md) §9 and §10.4 (register rows 6, 7, 9, AA, R6) require that authentication, authorization and catalog scope stay separate, that every bearer value reaches exactly one validator, and that a URL can narrow but never widen what a caller may do.

## Decision

1. **Three separate concepts** (plan.md §9.1). Authentication comes only from the credential; authorization is decided by Control on every request; catalog scope comes only from the URL and only narrows.
2. **Tenant first.** The tenant is resolved from the request endpoint (tenant hostname, or the single Quickstart tenant), never from the token's `iss`. Only that tenant's trusted issuers are considered.
3. **One token format per issuer.** Each `TrustedIssuer` declares `tokenFormat: JWT | OPAQUE_INTROSPECT`. A tenant with more than one introspection issuer for the same audience is rejected when saved.
4. **Fixed classification order** (plan.md §9.2), each value handed to exactly one validator, failure is `401` with no fallback: (1) `tg_` prefix with static tokens enabled, existing hash lookup; (2) compact JWS whose unverified `iss` is a tenant JWT issuer, JWT validation; (3) the single introspection issuer for this audience, RFC 7662; (4) otherwise `401` with an RFC 9728 `WWW-Authenticate`. Existing mounted tokens keep working without a prefix.
5. **JWT validation:** `typ: at+jwt`; JWKS signature with an `alg` allowlist (never `none`, no HMAC for external issuers); `iss`; `aud` equal to this Gateway's RFC 8707 resource identifier; `exp` and `nbf` with small skew; optional `jti` replay cache. JWKS keys may be cached with rate-limited refetch on unknown `kid`.
6. **Introspection:** client-authenticated (mTLS or `private_key_jwt`), checks `active`, `iss`, `aud`, `exp`, `token_type` and scopes. Positive results are cached at most `min(exp, 60 s)`, negative at most 5 s, purged on any revocation push. No decision cache may act as an offline ALLOW.
7. **Claim mapping** (plan.md §9.3). Each issuer has a validated claim-mapping template (Entra ID, Okta, Auth0, Keycloak, Ping, Google, introspection, or custom). Each token must yield exactly one profile: `SERVICE`, `DELEGATED_CLIENT` or `DELEGATED_EXCHANGE`. Zero or two matches reject. Delegation needs both the issuer's token and Control's delegation rule. IdP groups and roles never grant membership.
8. **`RequestContext` v2** in `common.schema.json` carries no device and keeps `credentialEpoch`, `expiresAtUnixMs` and `delegatedSessionEpoch`. Nested `act` chains map to `chain`, bounded by `maximumDepth`.
9. **`CatalogScope`** in `catalog.schema.json` is a type separate from `RequestContext`: `kind: ALL | AGENT | AGENT_GROUP | AGENT_IN_GROUP`, optional `agentId`, `agentGroupId`, `toolsets[]`, and `revision`.
10. **Routes** (plan.md §9.4): `/mcp` (`ALL`), `/mcp/agent/{agent}` (caller is the agent or holds a delegation for it), `/mcp/agent-group/{group}` (only tools permitted through that group; caller is an enabled member), `/mcp/agent-group/{group}/{agent}` (both). Segments are slugs `^[a-z0-9][a-z0-9-]{0,62}$`; agents and groups gain a slug field, since existing ids are not slugs. Every `tools/call` re-checks scope and authorization. A mismatch returns the same `403` or JSON-RPC error with no catalog, no fallback scope and no hint that a group exists. `?toolset=` filters visibility only. New combinations become named `CatalogScope` kinds, not new route shapes.
11. **Tool names** (plan.md §10.4). `toolId` is the internal id used by policy, audit and permits. `mcpName` (1 to 128 characters of `A-Z a-z 0-9 _ - .`, default `<packageAlias>.<tool>`) is unique within a catalog scope at enable time. An optional `compatibilityAlias` comes from a compatibility profile on the workload binding or catalog scope; the built-in `llm-strict` profile is `^[a-zA-Z0-9_-]{1,64}$`. Alias derivation is deterministic (replace, then truncate with an 8-hex `SHA-256(toolId)` suffix, then reject the enable on any remaining collision). Calls resolve `mcpName` or alias to `toolId` before policy.
12. **Standalone SDK identity** follows the same verification rules through adapter A4 (ADR [019](019-tool-sdk-authoring-contract.md)); production mode never accepts unverified claims.

Contract surface: issuer and route changes are specified in `packages/contracts/openapi/proposed/gateway-v1.proposed.yaml` and `control-v1.proposed.yaml`. The served `packages/contracts/openapi/gateway-v1.yaml` and `control-v1.yaml` change only when M1 implements each endpoint, so the served spec never advertises an unimplemented endpoint.

## Amendment (M1): device placement

Decision 8 removes the device from `RequestContext` but did not say where a call runs. The project owner decided:

1. **Every call runs on a device.** Client tools run on enrolled endpoint devices. Server executors are devices too: Quickstart's built-in executors already are, and each Tool Host or Gateway executor container is its own always-connected device entry. Disabling a device, group membership and the kill switch therefore govern server calls exactly like client calls. Identity still carries no device; the device belongs to the call, not to the caller.
2. **Control places the call** from the execution binding's device group (`RequestContext.bindingId`). Candidates are group members that are enabled, enrolled, approved and online under the client check-in policy. For a tool call, members that report that exact tool installed are preferred. If the caller (`userId`) owns any candidates, only those remain. One candidate is used as is; from several, Control picks the one with the fewest queued calls, with ties broken by a hash of the request id. No candidate means no tools are listed and calls are denied.
3. **The placement is stored** in the invocation's `EnterpriseContext.deviceId`, which the permit, the relay and the result all use. A replay of the same request keeps its device; the Gateway no longer compares devices.
4. **A person in the console still chooses a device**, which must belong to the binding's device group.

5. **Slugs** (decision on route segments) ship on `ControlAgent.slug` and `ControlAgentGroup.slug`: optional, pattern `^[a-z0-9][a-z0-9-]{0,62}$`, unique per tenant among agents and among agent groups (Control rejects a duplicate as a validation error). An agent and a group may share a slug because their routes differ. The console edits them as **Route slug**. The scoped routes that resolve them arrive with the Gateway router in M2; an agent or group without a slug has no scoped route.

A pool of executor containers is one device group, so scaling out adds devices and calls spread across them. `RequestContext` also gains `credentialKind` (mounted credentials are `STATIC_TOKEN`), `issuer`, `delegatedSessionEpoch` and `expiresAtUnixMs`, and `bindingId` becomes optional, matching the v2 definition.

## Alternatives Considered

- **Take the tenant from `iss`.** Rejected: two tenants trusting one IdP would be confused.
- **Combined "JWT or introspect" per issuer, or trying validators in turn.** Rejected: ambiguous and lets one validator's failure fall through to another.
- **Synthetic device ids for server agents.** Rejected (row 7). The M1 amendment does not reverse this: executor devices are real registered devices that run calls, not identities given to agents.
- **Device named by the workload binding.** Considered in M1 and not chosen: it ties an agent to one device and cannot spread work across a pool.
- **Ids in URLs.** Rejected (row 6) in favour of readable, stable slugs.
- **URL scope that grants access**, or a fallback to `ALL` on mismatch. Rejected: a URL must never widen authorization or leak group existence.
- **One name field for MCP and policy.** Rejected (row 9): clients' naming limits would leak into policy ids.
- **Optional verification in standalone mode.** Rejected (row R6).

## Security Impact

- Each credential has exactly one validation path; misclassification cannot downgrade validation.
- ID tokens, `alg: none`, HMAC from external issuers and wrong-audience tokens are refused.
- Delegation requires two independent approvals (issuer and Control); IdP group claims cannot escalate.
- Catalog scope only narrows; authorization is re-checked on every call; scope mismatches reveal nothing.
- Introspection caches are bounded and purged on revocation, preserving the no-offline-ALLOW invariant.

## Operational Impact

Tenants configure trusted issuers, claim templates and compatibility profiles (later in the Configuration menu, ADR [021](021-client-checkin-configuration-menu.md)). Gateway router and guard code change for the scoped routes. Existing mounted tokens continue unchanged.

## Compatibility Impact

`RequestContext` loses `deviceId` (MAJOR, in the v2 contract set). Every Control call that passes the context changes in M1. Agent and group records gain slugs. `tools/list` starts exposing `mcpName` instead of `toolId` as `name`; clients that cached raw `toolId` names may need the alias profile. `/mcp` keeps its meaning.

## Consequences

- Positive: enterprise IdPs work without per-tenant code; agents without devices are first-class; URLs are readable and safe.
- Negative: claim templates must be maintained per IdP and tested with recorded tokens.
- Negative: removing the device from the context touches many Gateway and Control call sites.

## Validation

- `common.schema.json`, `catalog.schema.json` and `identifiers.schema.json` with valid and invalid fixtures pass the contract checks.
- Conformance (plan.md §21): recorded sample tokens per shipped template, each yielding exactly one profile; classification-order cases; introspection cache bounds.
- Route cases: each scope kind, mismatch responses identical for existing and unknown groups, toolset filtering never affecting authorization.
- Alias derivation vectors including truncation and unresolvable collisions.
