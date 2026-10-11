# Error model

## Purpose

One error hierarchy with identical codes, retry semantics and MCP mapping in every language and both runtime modes.

## 1. Hierarchy

```text
ToolError (abstract)
├── ToolInputError                 code INVALID_ARGUMENT
├── ToolBusinessError(code, message, data?)
├── ToolRetryableError(code, message, retryAfter?)
├── UpstreamError(route, status?, upstreamRequestId?, effectUncertain, body?)
├── ToolConfigurationError         code CONFIGURATION
└── PlatformError(code)            raised by the SDK only
```

Java, Python and .NET use exceptions; TypeScript uses `Error` subclasses (thrown or returned as rejected promises).

## 2. Codes and mapping

| Raised as | Code | MCP result | `retryable` |
|---|---|---|---|
| `ToolInputError`, or automatic `inputSchema` failure | `INVALID_ARGUMENT` | Tool result, `isError: true` | false |
| `ToolBusinessError` | Author code, **declared** in the tool's `errors`. The packager fails on an undeclared literal code it can see; at runtime an undeclared code is reported as `UNDECLARED_ERROR` with the original code in `data.originalCode`. | Tool result, `isError: true` | false |
| `ToolRetryableError` | Author code, declared | Tool result, `isError: true` | true only when `requiresExecutionLedger` is false (effect `READ_ONLY` or `IDEMPOTENT`); otherwise false, and the downgrade is recorded |
| `UpstreamError` | `UPSTREAM_4XX`, `UPSTREAM_5XX`, `UPSTREAM_TIMEOUT`, `UPSTREAM_CONNECTION` | Tool result, `isError: true` | As for `ToolRetryableError`. If it escapes a ledger tool with `effectUncertain = true`, the invocation ends `OUTCOME_UNKNOWN`, not `FAILED`. |
| `ToolConfigurationError` | `CONFIGURATION` | Tool result, `isError: true` | false |
| `PlatformError` | `CANCELLED`, `DEADLINE_EXCEEDED`, `ROUTE_REFUSED`, `AUDIT_UNAVAILABLE`, `CLAIMS_UNAVAILABLE`, `CLIENT_TOOLS_UNAVAILABLE`, `UNSUPPORTED_TIER`, `CONCURRENCY_LIMIT` | Tool result or task status (plan.md §12.4) | fixed: only `DEADLINE_EXCEEDED` and `CONCURRENCY_LIMIT` on non-ledger tools are retryable |
| Any other uncaught error | `INTERNAL_TOOL_ERROR` | Tool result with the fixed message "The tool failed." | false |

`effectUncertain` is true when the request was fully sent and no complete response was received (timeout or reset after send). It is false for connection failures before send, and for any complete response. When the invocation ends `OUTCOME_UNKNOWN`, the tool result carries error code `OUTCOME_UNKNOWN` with `retryable: false` and the upstream code in `data.upstreamCode`; a task ends `failed` with that error (plan.md §12.4).

## 3. Result shape

```json
{
  "resultType": "complete",
  "isError": true,
  "content": [{ "type": "text", "text": "Order 123456 was not found." }],
  "structuredContent": {
    "error": { "code": "ORDER_NOT_FOUND", "message": "Order 123456 was not found.", "retryable": false, "data": {} }
  }
}
```

`content[0].text` equals `error.message`. `data` must validate against the declared error's optional `dataSchema` when one is declared.

## 4. Redaction

Messages and `data` pass the same redaction as logs: values of declared secrets and `sensitive` inputs or globals are replaced with `[REDACTED]`; messages are capped at 1 KiB and `data` at 8 KiB. Stack traces never reach the MCP result; they go to local diagnostics (standalone) or redacted audit diagnostics (governed).

## 5. Protocol errors

Malformed JSON-RPC, unknown tools, missing capabilities and version errors are JSON-RPC errors produced by the protocol layer ([protocol-layer.md](../protocol-layer.md)); tool code never sees them.

## Related Docs

- [execution.md](execution.md)
