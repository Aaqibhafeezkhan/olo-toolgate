# Protocol layer and adapter contracts A1 to A5

## Purpose

Defines the layer between the official MCP SDKs and the ToolGate tool runtime in standalone mode, and the five adapters that close the gaps found in the [compatibility record](sdk-compat/README.md). Every SDK implements these contracts with identical observable behaviour.

## 1. Division of work

| Owned by the official MCP SDK | Owned by the ToolGate protocol layer |
|---|---|
| Framing, stdio and Streamable HTTP transports, JSON-RPC, schema types, the legacy 2025-11-25 session path | Tool dispatch, per-request capability gating, identity verification before dispatch, `requestState`, Tasks methods, the error mapping ([authoring/errors.md](authoring/errors.md)), descriptor-digest reporting |

The layer **must not** use any API the official SDK marks experimental, and **must not** patch or fork the official SDK.

## 2. The layer's interface

```text
RequestEnvelope {
  jsonrpcId, method, params,
  meta      { protocolVersion, clientCapabilities, extensions },
  transport { kind: "stdio" | "http", headers?, peer }
}

ProtocolLayer.handle(envelope) ->
    Complete(result)
  | InputRequired(inputRequests, requestState)
  | Task(CreateTaskResult)
  | ProtocolError(code, message, data?)
```

Rules that hold in every language:

- **Version lock on stdio.** A stdio connection is locked to the protocol version of its first message; a later message for another version gets `ProtocolError(-32600)`. (TS and Python do this already; .NET accepts a later `initialize`, so the layer enforces it there.)
- **Capability per request.** In 2026-07-28 a feature is used only if the request's `meta.clientCapabilities` or `meta.extensions` declares it. A task is never returned to a request that did not declare the Tasks extension; a tool marked `async` then runs synchronously within its sync deadline or fails with `DEADLINE_EXCEEDED`.
- **Schemas as declared.** `tools/list` serves `inputSchema` and `outputSchema` byte-for-byte as in the descriptor (after JSON parsing; no SDK post-processing).
- **Digests.** `server/discover` result `_meta["io.ololabs.toolgate/descriptorDigests"]` is a map `toolId → toolDescriptorDigest`, plus `descriptorSetDigest`.
- **Errors.** Unknown tool, malformed params and missing capability are JSON-RPC errors; everything raised inside a handler becomes a tool result per [authoring/errors.md](authoring/errors.md).

## 3. Adapter contracts

### A1 Protocol core

**Used where:** the official SDK lacks the 2026-07-28 server core (Java 2.0.1).

- Implements `server/discover`, `tools/list`, `tools/call` (with `resultType`), per-request `_meta` validation, errors -32020, -32021 and -32022, and `subscriptions/listen` for `toolsListChanged`.
- Routes by version: a request carrying 2026-07-28 `_meta` goes to A1; `initialize` and other 2025-11-25 traffic goes to the official SDK's legacy path unchanged. Both versions are served on one URL and over stdio.
- Reuses the official SDK's schema types where they match the 2026-07-28 shape.
- **Acceptance:** the official conformance scenarios for 2026-07-28 servers that apply to tools, at Tier 1 level (37 / 37 required server scenarios), plus the ToolGate protocol cases.
- **Retirement:** when an official Java release passes the same 37 scenarios, a PR moves Java to it and removes A1.

### A2 Tasks

**Used in:** all four languages. The .NET Tasks package is not used, so semantics are identical.

- Methods: `CreateTaskResult` from `tools/call`, `tasks/get`, `tasks/update`, `tasks/cancel`, and `notifications/tasks` (status push over `subscriptions/listen`).
- State mapping, ownership and TTL as in plan.md §12.4 and §12.6. A task is visible only to the identity that created it; any other identity gets not-found, never forbidden.
- Storage through the port `TaskStore { create, get, transition, submitInput, requestCancel, purgeExpired }`; memory and SQLite implementations in standalone mode; Control in governed mode.
- **Acceptance:** ToolGate Tasks cases: capability per request, foreign identity not-found, TTL purge, state mapping, cancellation with and without `cancelIfAbandoned`, status push.

### A3 `requestState`

**Used in:** all four languages.

- A compact JWS, HS256, with a per-process random key (at least 256 bits) in standalone mode. In governed mode the SDK never mints or verifies `requestState`: Control mints it with the ASK outcome and verifies it on the retry, and the Gateway holds no key (ADR 013, decision 9).
- Payload `{toolId, argsHash, identity, scope, approvalId?, exp, nonce}`; `argsHash` is the `sha256:` digest of the JCS canonical arguments; `exp` at most 10 minutes after issue.
- Verified before any re-dispatch: signature, `exp`, same `toolId`, same `argsHash`, same identity and scope. Each `nonce` is accepted once within its lifetime.
- **Acceptance:** ToolGate MRTR cases: tampered payload, expired, other identity, other arguments, replay.

### A4 Identity

**Used in:** all four languages. No SDK's bearer support is used for verification.

- `verify(bearer) -> VerifiedIdentity {issuer, subject, claims, expiresAt} | Reject(reason)`.
- JWT: JWKS fetched over HTTPS and cached, refreshed on an unknown `kid` at most once per 30 s; pinned keys as an alternative; issuer and audience exact match; algorithm allowlist (`none` and HMAC always rejected); `exp` and `nbf` required with at most 60 s skew.
- Introspection: RFC 7662 with client credentials, `active` required, cache bounded by the token's `exp` and at most 5 minutes.
- Runs before dispatch; behaviour by mode in [runtime-modes.md](runtime-modes.md) §4.
- **Acceptance:** shared token fixtures, identical in every language: good, expired, not yet valid, wrong issuer, wrong audience, `alg: none`, HS256 with the public key as secret, unknown `kid` (then known after refresh), revoked by introspection.

### A5 Method routing

**Used where:** the official SDK has no stable custom-method hook (.NET 2.2.0, where the hook is experimental and omitted `resultType`; Java 2.0.1, where the handler map is private).

- The ToolGate transport handler sees each request first. It routes `tasks/*`, ToolGate-owned methods and 2026-07-28 tool calls that need A2 to A4 to the layer, adds `resultType` to results it produces, and passes everything else to the official SDK unchanged.
- In .NET it also serves both protocol versions on one URL, matching TS and Python.
- **Acceptance:** the protocol compatibility cases pass with and without the hook (TS and Python, which have an official hook, run them both ways).

## 4. Upgrade rule

An official SDK version changes only through a PR that re-runs the protocol compatibility suite, adds a new record under [sdk-compat/](sdk-compat/README.md), and updates the pinned table. The Java dependency is shaded; the others are isolated where the ecosystem allows, so a tool's own MCP SDK version cannot clash.

## Related Docs

- [Compatibility record](sdk-compat/README.md)
- [Runtime modes](runtime-modes.md)
