# Gateway API

Managed tool execution also follows the [device registry gates](../control-plane/device-registry.md):
trusted execution identity, current owner/device enablement, installed-client
approval and the applicable user/agent/device/tool/action/resource filters. A
policy-only API decision cannot enroll a device or execute a protected tool.
Quickstart REST forwarding gates the existing client relay in addition to the
selected client's registry state and filters.

## Purpose

The canonical [OpenAPI](../../packages/contracts/openapi/gateway-v1.yaml) documents
the v1 decision surface. Runtime bearer credentials supply identity; caller bodies
and forwarded principal headers cannot. Runtime responses carry server-generated
X-Request-Id, validated/generated W3C traceparent and Cache-Control: no-store.

`POST /v1/authorize` accepts:

```json
{"toolId":"files.read","action":"read","arguments":{"path":"workspace/readme.txt"}}
```

HTTP 200 carries canonical PolicyDecision: ALLOW/BLOCK, reason, policyVersion and
requestId. BLOCK is an evaluation result. Errors use ErrorEnvelope: VALIDATION
400/413/415/431, UNAUTHORIZED 401, FORBIDDEN 403, DEPENDENCY_UNAVAILABLE 429/503,
TIMEOUT 504, UNSUPPORTED 501 and NOT_FOUND 404. Error bodies expose no exception detail.
`POST /v1/permits` is UNSUPPORTED: no execution authority can be minted.

Separate management serves /v1/health/live, /v1/health/ready and /v1/metrics. These
routes are absent from runtime ingress. Keep management network-restricted.

`POST /mcp` is a restricted [2026-07-28 transport](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http).
One JSON-RPC request with integer/string ID and per-request metadata is accepted.
server/discover works and lists every supported version. Ping is not part of 2026-07-28
and returns method not found on that path. With the optional `localMcp` relay enabled, tools/list
returns device-scoped installed definitions applicable to this agent. ALLOW tools/call
requests are queued in Control for the next client poll and return a bounded result.
Managed tools use their fixed CUSTOM `runtime/<tool-id>` resource, and builtins use
registered extraction. ASK blocks. Without relay mode, discovery is empty and execution
is unsupported. Unknown methods, 2026-07-28 notifications and streaming subscriptions
reject; GET/DELETE return 405.

Session-era clients on `2025-11-25` or `2025-06-18` use the legacy adapter
([ADR 018](../adr/018-mcp-versions-legacy-adapter.md)). A request goes there when it has
no `_meta` protocol version and either calls `initialize` or names one of those versions
in `MCP-Protocol-Version`; a request with a `_meta` version is always judged by the
2026-07-28 rules. `initialize` echoes the requested legacy version, advertises
`tools.listChanged: false` and returns an opaque `Mcp-Session-Id`. The id is random,
never stored and never consulted: every request is authenticated by its bearer and
authorized against the current catalog. Notifications get 202. `ping`, `tools/list`
and `tools/call` run on the same core as 2026-07-28 requests, without `resultType`.
Any other version gets -32022 with the supported list. The legacy GET SSE stream and
`notifications/tools/list_changed` are not offered yet.
Mirrored version/method/name headers are checked, including encoded names. Early
transport/auth/limit failures use HTTP errors; parsed RPC failures use sanitized
JSON-RPC errors. See [client relay configuration and progress](../client/server-mcp.md)
for the device-certificate authorization, permission replacement and retry protocol.
Parse/invalid-frame errors include `id: null`; unsupported notifications receive
an ordinary HTTP error without a JSON-RPC response, consistent with
[JSON-RPC 2.0](https://www.jsonrpc.org/specification).
