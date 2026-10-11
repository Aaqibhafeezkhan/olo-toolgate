# Testing tools (language-neutral)

## Purpose

Defines `ToolHarness`, the testing library every SDK ships, and the recorded exchange format it shares across languages. A harness runs tools in `test` mode ([runtime-modes.md](../runtime-modes.md) §4) with the same runtime, validation and error model as the standalone server, so a passing test means the same behaviour in standalone and governed mode. The per-language files name the package; they **must not** change the behaviour below.

## 1. Test mode

- Only the testing library can start test mode; the runtime entry points refuse `TOOLGATE_MODE=test`.
- Deployment values come from harness fixtures shaped like `toolgate.dev.json`: `globals`, `secrets`, `claims` and `destinations`. Nothing is read from the environment or a `toolgate.dev.json` file.
- Claims come only from the fixtures, and results carry `_meta["io.ololabs.toolgate/identity"] = "fixture"`, as in development mode.
- Every route call is answered by a recorded exchange (§3) instead of the network. Route auth is named in the recorded request but never resolved, so no secret, token or token-endpoint call leaves the runtime.
- A route call no recorded exchange answers fails with `ROUTE_REFUSED`, and is still recorded.
- Calls go through the MCP protocol layer. Async tools run as tasks; the harness polls them and returns the final result.

## 2. Harness API

| Operation | Meaning |
|---|---|
| build with fixtures | `global(name, value)`, `secret(name, value)`, `claim(name, value)`, `destination(hostSetting, host)`, recorded exchanges inline or from a resource |
| `call(tool, arguments)` | Calls a tool by local name, `toolId` or `mcpName`; returns the `CallToolResult` with typed views (`output`, `errorCode`) |
| `routeCalls()` | Every route call so far, in order: route id, method, variables, query, headers, auth id, body, and whether an exchange answered it |
| `runExamples()` | Runs every declared `Example` with only the exchanges its `exchanges` resource holds (§4) |

## 3. Recorded exchange format

A JSON array of exchanges. Unknown fields are an error.

```json
[
  {
    "request": { "route": "orders.get", "method": "GET", "variables": { "orderId": "7" }, "query": { "expand": ["lines"] } },
    "response": { "status": 200, "headers": { "Content-Type": "application/json" }, "body": { "id": "7" } }
  },
  {
    "request": { "route": "orders.cancel" },
    "response": { "failure": "TIMEOUT_AFTER_SEND" }
  }
]
```

| Field | Meaning |
|---|---|
| `request.route` | Route id; required |
| `request.method`, `variables`, `query`, `body`, `bodyText`, `bodyBase64` | When present, the call must have exactly this value. `variables` values and `query` values are strings |
| `request.headers` | When present, the call must send each named header with this value; other headers are not compared |
| `response.status` | HTTP status, default 200. A non-2xx status gives `UPSTREAM_4XX` or `UPSTREAM_5XX` as on the network |
| `response.headers` | Header name to a string or an array of strings |
| `response.body` \| `bodyText` \| `bodyBase64` | The response body as JSON, UTF-8 text or base64 bytes; none is an empty body. The route's `maxResponseBytes` applies |
| `response.failure` | Instead of a response: `CONNECTION_REFUSED` (`UPSTREAM_CONNECTION`, effect certain not to have happened), `RESET_AFTER_SEND` (`UPSTREAM_CONNECTION`, effect uncertain), `TIMEOUT_AFTER_SEND` (`UPSTREAM_TIMEOUT`, effect uncertain) |

The first exchange whose request matches answers the call; an exchange can answer any number of calls. The runtime's request form is the same object with every field present, plus `destination` and `auth` (the auth id or null).

## 4. Examples

An example passes when its expected error code is the result's error code, or its expected output equals the structured output as JSON, or, with neither, the call succeeds. An example that makes a route call no exchange in its resource answers fails for missing a recorded mock exchange, whatever its result.

## Related Docs

- [execution.md](execution.md) §7, [http.md](http.md), [../runtime-modes.md](../runtime-modes.md)
