<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 018: MCP protocol versions and the legacy compatibility adapter

**Status:** Accepted (design gate D1). Amended in M1: decisions 2 and 3 are withdrawn, see [Amendment](#amendment-m1-legacy-adapter-withdrawn).

## Context

The Gateway's `/mcp` route already implements the stateless MCP 2026-07-28 core: `server/discover` advertises only `2026-07-28`, every request must carry the protocol version and client capabilities in `_meta`, `Mcp-Method` and `Mcp-Name` headers are checked, and errors -32020 and -32022 are returned. It still answers `ping`, which 2026-07-28 removed, it accepts POST only with keep-alive off, and it rejects all notifications (plan.md §2.1). Many deployed MCP clients still speak session-based `2025-11-25` or `2025-06-18`, and long-running tools need the Tasks extension, which not every client negotiates.

[plan.md](../sdk-plan/plan.md) §10.1 to §10.3, §12.4 and §12.5 (register rows B, F, AB) require a version model that keeps the core stateless, supports older clients for a bounded period, and never starts work a client cannot collect.

## Decision

1. **Modern core: 2026-07-28** (plan.md §10.1). No sessions; every request is independently authenticated and authorized. `server/discover` advertises versions, capabilities, `extensions` (including `io.modelcontextprotocol/tasks`) and server info. Every result carries `resultType`: `complete`, `input_required` or `task`. `subscriptions/listen` supports opt-in `toolsListChanged`. Logging is per request. `ping` is removed from the modern path. Errors use -32020 `HeaderMismatch`, -32021 `MissingRequiredClientCapability` and -32022 `UnsupportedProtocolVersion`, the last with the supported versions. W3C trace context in `_meta` is propagated. A broken stream loses the request; non-idempotent safety comes from durable tasks and the execution ledger, not transport redelivery.
2. **Legacy adapter** (plan.md §10.2, decision B). A separate module in front of the core supports exactly `2025-11-25` and `2025-06-18` for at least twelve months, matching the MCP deprecation window. Any other version gets `UnsupportedProtocolVersion`. It translates `initialize`, `notifications/initialized`, `ping`, `Mcp-Session-Id`, the GET SSE stream and `notifications/tools/list_changed` onto the stateless core.
3. **Sessions are a transport artifact only.** The adapter re-authenticates and re-authorizes every request and never pins identity or catalog scope to a session. The core never gains session state.
4. **Catalog behaviour** (plan.md §10.3). `tools/list` is deterministic, with `ttlMs` and `cacheScope: private`. The cache key is protocol version, negotiated extensions, `CatalogScope`, identity and catalog revision. `outputSchema`, annotations and task support are exposed only where the negotiated version and extensions allow.
5. **Tasks mapping** (plan.md §12.4). The server decides per request whether to return `resultType: "task"`, and never returns a task to a request that did not declare the Tasks extension. A `CreateTaskResult` is durably created before the response. ToolGate states map to MCP statuses as in plan.md §12.4; `AWAITING_APPROVAL` becomes `input_required` with one URL-mode elicitation; `OUTCOME_UNKNOWN` becomes `failed` with `retryable: false`.
6. **Clients without Tasks** (plan.md §12.5): never start work the client cannot collect. Async tools, or tools that may exceed the sync deadline, are hidden from `tools/list` for that request's capabilities and a direct call gets -32021 naming the Tasks extension, unless the admin opts the tool into bounded sync mode. ASK approval for an MRTR client returns `InputRequiredResult` and executes only on the retry after approval, with a signed, bound, expiring `requestState`. A legacy client gets today's error with an approval link and no execution. A client tool that cannot be picked up within the sync deadline fails with `PICKUP_TIMEOUT` before anything runs.
7. **SDK side** (plan.md §5.10, summary). Standalone SDK runtimes use the pinned official MCP SDKs where they pass the 2026-07-28 conformance scenarios, and ToolGate adapters A1 (Java protocol core), A2 (Tasks), A3 (`requestState`), A4 (identity) and A5 (method routing) for every gap, as frozen by ADR [019](019-tool-sdk-authoring-contract.md). The same version model applies in both modes.

Contract surface: task fields and states are in `packages/contracts/schemas/v2/invocation.schema.json`; scoped routes, `subscriptions/listen` and legacy behaviour are described in `packages/contracts/openapi/proposed/gateway-v1.proposed.yaml`. The served `packages/contracts/openapi/gateway-v1.yaml` changes only when M1 implements each endpoint, so the served spec never advertises an unimplemented endpoint.

## Amendment (M1): legacy adapter withdrawn

ToolGate has no production deployments, so there are no existing clients to keep working. The project owner chose to serve only 2026-07-28 at the Gateway. Decisions 2 and 3 are withdrawn and register row B is reversed: the adapter shipped in M1 (PR #44) was removed. A request with no `_meta` protocol version that calls `initialize` or names another version in `MCP-Protocol-Version` gets -32022 `UnsupportedProtocolVersion` listing only `2026-07-28`; `server/discover` lists only `2026-07-28`; GET and DELETE on `/mcp` return 405. Decision 6's legacy-client row no longer applies at the Gateway. The SDK side (decision 7) is unchanged: standalone runtimes may still accept older versions through the official SDKs, and the MCP proxy's client role (plan.md §16) still probes older upstream servers.

## Alternatives Considered

- **Modern only.** Rejected: existing clients would stop working on upgrade.
- **Unbounded legacy support.** Rejected: maintenance cost grows without limit; twelve months matches the MCP deprecation window.
- **Session state inside the core, or identity pinned to a legacy session.** Rejected: a session would become a cached authorization.
- **Run async work for clients without Tasks and drop the result.** Rejected (row F): effects without a way to collect the outcome.
- **Rely on each official SDK's own Tasks support.** Rejected (row AB): only .NET has it.

## Security Impact

- No session ever carries identity, scope or a decision; every legacy request is re-authenticated and re-authorized.
- Capability gating is per request, so a client cannot receive a task or MRTR flow it did not declare.
- `requestState` is signed and bound to identity, scope, tool and arguments, so approval retries cannot be redirected.
- The adapter is a separate module, so legacy parsing bugs are isolated from the core.

## Operational Impact

The Gateway gains GET SSE and notification handling for legacy clients and `subscriptions/listen` for modern ones, which changes its HTTP transport (keep-alive and streaming). Operators can see protocol versions per request in metrics and audit. Removal of legacy versions after the window is announced in release notes.

## Compatibility Impact

Existing 2026-07-28 clients see no change except that `ping` is refused on the modern path. Clients on `2025-11-25` and `2025-06-18`, which are rejected today, start working. Other versions keep receiving -32022. Task and MRTR behaviour is additive and capability gated.

## Consequences

- Positive: existing MCP clients keep working while the core stays stateless.
- Positive: long-running and approval-gated tools behave safely for every client class.
- Negative: two protocol paths to test and maintain for at least twelve months.
- Negative: some tools are invisible to clients without Tasks unless bounded sync mode is chosen.

## Validation

- Official MCP conformance scenarios for 2026-07-28 and 2025-11-25 servers against the Gateway, plus ToolGate cases for `2025-06-18`.
- ToolGate protocol cases (plan.md §21): no task to a non-declaring request, hidden async tools, -32021, MRTR retry-after-approval, `requestState` tampering and replay, session not pinning identity.
- `invocation.schema.json` and `protocol.schema.json` fixtures pass the contract checks.
- Review of `gateway-v1.proposed.yaml` against this ADR before M1.
