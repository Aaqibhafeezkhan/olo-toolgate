# Gateway API

Managed tool execution also follows the [device registry gates](../control-plane/device-registry.md):
trusted execution identity, current owner/device enablement, installed-client
approval and the applicable user/agent/device/tool/action/resource filters. A
policy-only API decision cannot enroll a device or execute a protected tool.

For every route with example requests and responses, see the
[HTTP and MCP API reference](api-reference.md#2-gateway-runtime-api).

## Purpose

The canonical [OpenAPI](../../packages/contracts/openapi/gateway-v1.yaml) documents
the runtime surface. Runtime bearer credentials supply identity; caller bodies
and forwarded principal headers cannot. Runtime responses carry server-generated
X-Request-Id, validated/generated W3C traceparent and Cache-Control: no-store.

Runtime routes:

| Route | Auth | Purpose |
|---|---|---|
| `GET /.well-known/olo-toolgate-client` | none | Verified signed tenant discovery fetched from Control |
| `GET /api/public/v1/clients/configuration` | none | `{"serverUrl"}` of the verified Control origin |
| `POST /access/catalog` | workload bearer | Enabled tools for the authenticated agent |
| `POST /access/invocations` | workload bearer | Submit exact arguments; reserve a Control permit when queued |
| `POST /mcp` | workload bearer | Stateless MCP 2026-07-28 ingress |

`POST /access/invocations` requires an `Idempotency-Key` header and accepts:

```json
{"toolId":"files.read","action":"read","arguments":{"path":"workspace/readme.txt"}}
```

HTTP 200 carries `EnterpriseAuthorizationOutcome`: the durable invocation and, when
Control queued it, the reservation with its signed permit. `PENDING_APPROVAL`
carries no capability. A tool outside the agent's current catalog returns
`403 FORBIDDEN`. Errors use ErrorEnvelope: VALIDATION 400/413/415/431,
UNAUTHORIZED 401, FORBIDDEN/TENANT_MISMATCH 403, NOT_FOUND/TENANT_UNAVAILABLE 404,
MISDIRECTED_REQUEST 421, DEPENDENCY_UNAVAILABLE 429/503, TIMEOUT 504 and
UNSUPPORTED 405/501. Error bodies expose no exception detail.

Separate management serves `/v1/health/live`, `/v1/health/ready`, `/v1/credentials`
(loaded credential digest and count) and `/v1/metrics`. These routes are absent from
runtime ingress. Keep management network-restricted. `SIGHUP` reloads the mounted
credential file atomically; an invalid file leaves the previous set live.

## MCP

`POST /mcp` is a restricted [2026-07-28 transport](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http).
One JSON-RPC request with integer/string ID and per-request metadata is accepted.
`server/discover` lists `2026-07-28`. `tools/list` returns the enabled tools of the
agent's current Control catalog. `tools/call` submits the exact arguments to
Control, which places the call on one device of the execution binding's device
group. A Tool Host runs it with a device-bound permit, either dispatched directly
(configured `toolHosts`) or fetched from Control in pull mode; an enrolled client
receives it through the Control relay on its next check-in. Package tools use their
fixed CUSTOM `tool://<tool-id>` resource. ASK returns a result with `isError: true`
and `structuredContent: {"invocationId", "state": "PENDING_APPROVAL"}`; nothing runs
until it is approved. Ping is not part of 2026-07-28 and returns method not found.
Unknown methods, notifications and streaming subscriptions reject; GET/DELETE
return 405.

Only 2026-07-28 is served ([ADR 018 amendment](../adr/018-mcp-versions-legacy-adapter.md#amendment-m1-legacy-adapter-withdrawn)).
A request with no `_meta` protocol version that calls `initialize` or names another
version in `MCP-Protocol-Version` gets -32022 listing `2026-07-28`; a request with a
`_meta` version is always judged by the 2026-07-28 rules.
Mirrored version/method/name headers are checked, including base64-encoded names. Early
transport/auth/limit failures use HTTP errors; parsed RPC failures use sanitized
JSON-RPC errors. See [client relay configuration and progress](../client/server-mcp.md)
for the device-certificate authorization, permission replacement and retry protocol.
Parse/invalid-frame errors include `id: null`; unsupported notifications receive
an ordinary HTTP error without a JSON-RPC response, consistent with
[JSON-RPC 2.0](https://www.jsonrpc.org/specification).
