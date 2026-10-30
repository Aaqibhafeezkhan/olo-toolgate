<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# HTTP and MCP API reference

This page lists every HTTP route served by the **Gateway** container and the
**Control** container (the admin container that also serves the console), plus
the MCP methods exposed on the Gateway's `/mcp` endpoint. Each entry gives the
caller, the purpose and an example request and response.

The canonical field definitions are the checked-in contracts:

- [gateway-v1.yaml](../../packages/contracts/openapi/gateway-v1.yaml)
- [control-v1.yaml](../../packages/contracts/openapi/control-v1.yaml)
- [quickstart-v1.yaml](../../packages/contracts/openapi/quickstart-v1.yaml)
- [JSON schemas](../../packages/contracts/schemas/README.md)

The examples on this page use the field names from those schemas, with safe
placeholder values. Optional fields are often left out. Where this page and a
schema disagree, the schema wins. Please report the difference.

Version values such as `1.0.0-dev.1` are illustrative. The branch series and
channel are defined in [version.json](../../version.json); the running service
reports its actual build version.

Route inventory was checked against `apps/gateway/src/http.rs`,
`apps/gateway/src/mcp.rs` and the JAX-RS resources in
`apps/control-plane/src/main/java/io/ololabs/toolgate/control/adapter/`.

## Contents

1. [Conventions](#1-conventions)
2. [Gateway runtime API](#2-gateway-runtime-api)
3. [Gateway MCP API (`POST /mcp`)](#3-gateway-mcp-api-post-mcp)
4. [Gateway management API](#4-gateway-management-api)
5. [Control: public and discovery routes](#5-control-public-and-discovery-routes)
6. [Control: console session, settings and administration](#6-control-console-session-settings-and-administration)
7. [Control: directory collections](#7-control-directory-collections)
8. [Control: configuration review, import/export, audit and bundles](#8-control-configuration-review-importexport-audit-and-bundles)
9. [Control: access, invocations and approvals](#9-control-access-invocations-and-approvals)
10. [Control: endpoint devices and enrollment](#10-control-endpoint-devices-and-enrollment)
11. [Control: MCP client relay](#11-control-mcp-client-relay)
12. [Control: packages, Tool Hosts and provisioning](#12-control-packages-tool-hosts-and-provisioning)
13. [Control: marketplace, fleet, builder and vault](#13-control-marketplace-fleet-builder-and-vault)
14. [Quickstart composition routes](#14-quickstart-composition-routes)
15. [Control management endpoints](#15-control-management-endpoints)
16. [Known gaps between contracts and implementation](#16-known-gaps-between-contracts-and-implementation)

## 1. Conventions

### Listeners and ports

| Container | Listener | Default | Content |
|---|---|---|---|
| Gateway | runtime (`listen`) | `0.0.0.0:8081` in the [example](../examples/gateway-online.json) | Discovery, `/access/*`, `/mcp` |
| Gateway | management (`managementListen`) | `0.0.0.0:9091` | `/v1/health/*`, `/v1/credentials`, `/v1/metrics` |
| Control | HTTP (`QUARKUS_HTTP_PORT`) | `8082` | Console (`/console/`) and every `/api/...` route |
| Control | direct TLS (device mTLS) | `8443` in Quickstart | Discovery, enrollment, device, Tool Host and relay routes that need a client certificate |
| Control | management (`QUARKUS_MANAGEMENT_PORT`) | `9092` | `/q/health/live`, `/q/health/ready`, `/q/metrics` |

Keep both management listeners on a private network. They have no authentication.

### Authentication

| Caller | Credential | Used on |
|---|---|---|
| Agent / MCP client | `Authorization: Bearer <workload token>`. The Gateway matches the SHA-256 of the token against its mounted credentials file. | Gateway `/access/*`, `/mcp` |
| Console user / administrator | `Authorization: Bearer <RS256 JWT>` with `sub`, `tenant_id`, `iat` and `exp` (15-minute lifetime). Quickstart issues it from `/api/quickstart/v1/login`. | Control `/api/control/v1/...` management routes |
| Gateway → Control | Service JWT with the `toolgate-relay-gateway` role | Control `/api/control/v1/access/{invocations,invocations/reserve,dispatches,health}` and `/api/control/v1/mcp/{catalog,requests,responses}` |
| Enrolled device or Tool Host | TLS client certificate over direct TLS | Control `/api/control/v1/endpoint/check-in`, `/mcp/authorize`, `/mcp/results`, `/access/permits/consume`, `/tool-hosts/*` and similar |
| Anyone | None | Discovery, public client downloads, health |

Management permission comes from the caller's enabled directory membership and
roles in Control, not from token claims. A valid token for another tenant gets
`403 TENANT_MISMATCH`.

### Tenant routing headers

Both containers select the tenant from the HTTP `Host` authority, never from a
forwarded header.

- An unknown host returns `404 TENANT_UNAVAILABLE`.
- Duplicate or conflicting `Host` values return `421 MISDIRECTED_REQUEST`.
- The optional `Toolgate-Tenant-ID: <canonical UUID>` header is an assertion
  only. A malformed or duplicated value returns `400 INVALID_TENANT_ID`. A
  different tenant returns `403 TENANT_MISMATCH`.

See [tenant routing](../control-plane/tenant-routing.md) and
[Gateway configuration](../gateway/configuration.md).

### Common request headers

| Header | Meaning |
|---|---|
| `Content-Type: application/json` | Required on JSON bodies. The Gateway returns `415` otherwise. |
| `Idempotency-Key` | Retry key for mutations: 1–128 characters from `[A-Za-z0-9._-]`. Required by Gateway `POST /access/invocations` and by most Control mutations. Same key and same body replays the result. Same key and a different body returns `409 CONFLICT`. |
| `If-Match` | The current `revision` for directory updates and deletes. A stale value returns `409`. |
| `traceparent` | Optional W3C trace context. The Gateway validates it or generates one. |

Every Gateway response carries a server-generated `X-Request-Id`, a
`traceparent` and `Cache-Control: no-store`. Control responses carry
`Cache-Control: no-store`.

### Error bodies

Most failures use `ErrorEnvelope`:

```json
{"code": "FORBIDDEN", "requestId": "gw-4f1c0d0e9a7b4c3e8f2a1b6c5d4e3f20", "retryable": false}
```

| `code` | HTTP status |
|---|---|
| `VALIDATION`, `INVALID_TENANT_ID` | 400 (413 body too large, 415 media type, 431 headers too large) |
| `UNAUTHORIZED` | 401, with `WWW-Authenticate: Bearer` |
| `FORBIDDEN`, `TENANT_MISMATCH` | 403 |
| `NOT_FOUND`, `TENANT_UNAVAILABLE` | 404 |
| `CONFLICT` | 409 (stale revision, idempotency mismatch) |
| `MISDIRECTED_REQUEST` | 421 |
| (Control only) device suspended | 423, `retryable: true` |
| `DEPENDENCY_UNAVAILABLE` | 503, or 429 with `Retry-After: 1` when Gateway rate or concurrency limits are hit; `retryable: true` |
| `UNSUPPORTED` | 501, or 405 for a wrong HTTP method |
| `TIMEOUT` | 504, `retryable: true` |
| `INTERNAL` | 500 |

Some Control refusals name the records you must fix, using `ControlErrorDetail`:

```json
{"error": "UNRESOLVED_IDENTITIES", "ids": ["user-alice"], "requestId": "req-01"}
```

The `error` value is one of `UNRESOLVED_IDENTITIES`, `REPLACE_CONFLICTS`,
`ONE_DEVICE_PER_ACCOUNT`, `REQUIRED_REVIEWS_CHANGE` or `RATE_LIMITED`. See
[errors and idempotency](errors-and-idempotency.md).

---

## 2. Gateway runtime API

Base URL: the tenant's public Gateway origin, for example `https://gateway.example.com`.

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/.well-known/olo-toolgate-client` | None | Signed tenant discovery document, fetched from Control and verified |
| GET | `/api/public/v1/clients/configuration` | None | The verified Control URL for installers |
| POST | `/access/catalog` | Workload bearer | Tools this agent may call |
| POST | `/access/invocations` | Workload bearer | Submit a tool call and reserve a permit |
| POST | `/mcp` | Workload bearer | MCP 2026-07-28 ingress ([section 3](#3-gateway-mcp-api-post-mcp)) |

Any other path returns `404 NOT_FOUND`. A wrong method on a known path returns
`405` with `Allow: POST`.

### GET /.well-known/olo-toolgate-client

The Gateway fetches this document from its fixed `publicDiscovery.controlOrigin`
and checks the v2 signature, tenant UUID and both origins. It returns the bytes
unchanged. It never serves a cached copy. If `publicDiscovery` is not configured,
or Control does not know the tenant, the response is `404 TENANT_UNAVAILABLE`.

```http
GET /.well-known/olo-toolgate-client HTTP/1.1
Host: gateway.example.com
```

```json
{"payload": "<base64url signed TenantDiscovery>", "signature": "<base64url signature>"}
```

The signed payload is a `TenantDiscovery` document:

```json
{
  "protocolVersion": 2,
  "tenantId": "10000000-0000-4000-8000-000000000001",
  "issuer": "https://control.example.com",
  "audiences": ["control.example.com", "gateway.example.com"],
  "serverId": "server-01",
  "organization": "Example Corp",
  "controlUrl": "https://control.example.com",
  "gatewayUrl": "https://gateway.example.com",
  "verificationUri": "https://control.example.com/console/#enroll",
  "minimumClientVersion": "0.10.0",
  "issuedAtUnixMs": 1792800000000,
  "expiresAtUnixMs": 1792800300000,
  "keySetVersion": 1,
  "discoveryKeys": {"keys": [{"kty": "RSA", "use": "sig", "alg": "RS256", "kid": "disc-1", "n": "<modulus>", "e": "AQAB"}]},
  "deviceIssuerKeyId": "dev-1",
  "deviceAuthorities": [{"keyId": "dev-1", "state": "ACTIVE", "certificatePem": "-----BEGIN CERTIFICATE-----..."}]
}
```

### GET /api/public/v1/clients/configuration

```http
GET /api/public/v1/clients/configuration HTTP/1.1
Host: gateway.example.com
```

```json
{"serverUrl": "https://control.example.com"}
```

### POST /access/catalog

Returns the enabled tool definitions for the authenticated agent. The body must
be `{}` or contain only `agentId`, and `agentId` must equal the agent bound to the
token. Otherwise the response is `400 VALIDATION`.

```http
POST /access/catalog HTTP/1.1
Host: gateway.example.com
Authorization: Bearer <workload token>
Content-Type: application/json

{"agentId": "agent-support-bot"}
```

```json
{
  "tools": [
    {
      "toolId": "files.read",
      "action": "read",
      "description": "Read a text file from the workspace",
      "enabled": true,
      "inputSchema": {"type": "object", "properties": {"path": {"type": "string"}}, "required": ["path"]},
      "toolDigest": "3f1e…64 hex…",
      "packageDigest": "9a7c…64 hex…"
    }
  ]
}
```

### POST /access/invocations

Submits exact arguments to Control's current group authority. If Control queues
the call, the Gateway reserves a signed permit for it at once. Only a `RESERVED`
invocation carries a permit. `PENDING_APPROVAL` carries no capability.

The invocation ID is derived from the caller's identity and `Idempotency-Key`. A
retry with the same key returns the same invocation and never creates a second
one.

```http
POST /access/invocations HTTP/1.1
Host: gateway.example.com
Authorization: Bearer <workload token>
Content-Type: application/json
Idempotency-Key: ticket-4711-read-1

{"toolId": "files.read", "action": "read", "arguments": {"path": "workspace/readme.txt"}}
```

Response (`200`, `EnterpriseAuthorizationOutcome`):

```json
{
  "invocation": {
    "id": "inv-6c1f0e…",
    "requestDigest": "b5d4…64 hex…",
    "evaluation": {
      "context": {
        "requestId": "inv-6c1f0e…",
        "tenantId": "10000000-0000-4000-8000-000000000001",
        "mode": "SERVICE",
        "agentId": "agent-support-bot",
        "workloadBindingId": "wb-support-bot",
        "chain": [{"agentId": "agent-support-bot", "workloadBindingId": "wb-support-bot"}],
        "bindingId": "exec-default",
        "deviceId": "device-host-a"
      },
      "toolId": "files.read",
      "action": "read",
      "argumentsDigest": "a1c2…64 hex…",
      "resources": [{"kind": "FILE", "locator": "workspace/readme.txt"}],
      "toolDigest": "3f1e…64 hex…",
      "packageDigest": "9a7c…64 hex…",
      "nowUnixMs": 1792800000000,
      "authorityRevision": 42,
      "online": true
    },
    "state": "RESERVED",
    "revision": 2,
    "authorizationEpoch": 7,
    "expiresAtUnixMs": 1792800010000,
    "completedResources": [],
    "diagnosticId": "diag-81f2"
  },
  "reservation": {
    "invocation": {"id": "inv-6c1f0e…", "state": "RESERVED", "revision": 2, "…": "same invocation as above"},
    "permit": {"jws": "eyJhbGciOiJSUzI1NiIs…"}
  }
}
```

`invocation.state` is one of `PENDING_APPROVAL`, `QUEUED`, `RESERVED`,
`DISPATCHED`, `EXECUTING`, `SUCCEEDED`, `FAILED`, `CANCELLED`, `EXPIRED`,
`PARTIAL` or `OUTCOME_UNKNOWN`. A tool that is not in the agent's catalog, or a
`toolId`/`action` pair that does not match, returns `403 FORBIDDEN`. A missing or
invalid `Idempotency-Key` returns `400 VALIDATION`.

---

## 3. Gateway MCP API (`POST /mcp`)

`/mcp` is a stateless implementation of the
[MCP 2026-07-28 Streamable HTTP transport](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http).
Only `POST` is served. `GET` and `DELETE` return `405`. Each HTTP request carries
exactly one JSON-RPC request. There is no session and no `initialize` step.

### Required headers

| Header | Value |
|---|---|
| `Authorization` | `Bearer <workload token>` |
| `Content-Type` | `application/json` |
| `Accept` | Must list both `application/json` and `text/event-stream` |
| `MCP-Protocol-Version` | `2026-07-28`. Must equal `params._meta["io.modelcontextprotocol/protocolVersion"]`. |
| `Mcp-Method` | Must equal the JSON-RPC `method` |
| `Mcp-Name` | Only for `tools/call`. Must equal `params.name`. Non-ASCII names may be sent as `=?base64?<base64 of UTF-8 name>?=`. |

### Required body shape

- `jsonrpc` is `"2.0"`. `id` is an integer, or a string of at most 128 characters.
- Only the keys `jsonrpc`, `id`, `method` and `params` are allowed. `params` must be an object.
- `params._meta["io.modelcontextprotocol/protocolVersion"]` is `"2026-07-28"`.
- `params._meta["io.modelcontextprotocol/clientCapabilities"]` is an object (it may be `{}`).

### Supported methods

| Method | Result |
|---|---|
| `server/discover` | Supported versions and capabilities |
| `tools/list` | The caller's enabled tools, looked up again on every request |
| `tools/call` | Authorizes and runs one tool, then returns its result |
| anything else (including `ping` and `initialize`) | `-32601 Method not found` (HTTP 404), or `-32022` for session-era `initialize` |

Every successful result has `"resultType": "complete"` and
`_meta["io.modelcontextprotocol/serverInfo"] = {"name": "olo-toolgate-gateway", "version": "<release>"}`.
`ttlMs` is `0` and `cacheScope` is `"private"`, so clients must not cache
discovery.

### server/discover

```http
POST /mcp HTTP/1.1
Host: gateway.example.com
Authorization: Bearer <workload token>
Content-Type: application/json
Accept: application/json, text/event-stream
MCP-Protocol-Version: 2026-07-28
Mcp-Method: server/discover

{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "server/discover",
  "params": {
    "_meta": {
      "io.modelcontextprotocol/protocolVersion": "2026-07-28",
      "io.modelcontextprotocol/clientCapabilities": {}
    }
  }
}
```

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "supportedVersions": ["2026-07-28"],
    "capabilities": {"tools": {}},
    "ttlMs": 0,
    "cacheScope": "private",
    "resultType": "complete",
    "_meta": {"io.modelcontextprotocol/serverInfo": {"name": "olo-toolgate-gateway", "version": "1.0.0-dev.1"}}
  }
}
```

### tools/list

Returns only enabled tools from the agent's current Control catalog. These are
tools applicable to this agent through its grants and execution binding.

```http
POST /mcp HTTP/1.1
Mcp-Method: tools/list
(other headers as above)

{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/list",
  "params": {"_meta": {"io.modelcontextprotocol/protocolVersion": "2026-07-28", "io.modelcontextprotocol/clientCapabilities": {}}}
}
```

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "tools": [
      {
        "name": "files.read",
        "description": "Read a text file from the workspace",
        "inputSchema": {"type": "object", "properties": {"path": {"type": "string"}}, "required": ["path"]}
      }
    ],
    "ttlMs": 0,
    "cacheScope": "private",
    "resultType": "complete",
    "_meta": {"io.modelcontextprotocol/serverInfo": {"name": "olo-toolgate-gateway", "version": "1.0.0-dev.1"}}
  }
}
```

If Control cannot provide the catalog, the response is
`-32000 "Local tool discovery failed"` with `data.code` set to the `ErrorCode`.

### tools/call

`params` may contain only `name`, `arguments` and `_meta`. The Gateway looks up
the tool's `action` in the catalog, submits the exact arguments to Control and
then follows one of these paths:

1. **Tool Host, direct.** Control placed the call on a Tool Host listed in the
   Gateway's `toolHosts`. The Gateway reserves a permit and posts it to that
   host's `/v1/dispatch`.
2. **Tool Host, pull mode.** Control placed the call on a Tool Host that is not
   in `toolHosts`. The Gateway hands the dispatch to Control
   (`POST /api/control/v1/access/dispatches`). The host fetches it, and the
   Gateway polls the status.
3. **Client device relay.** Control placed the call on an enrolled client (the
   permit request returns `409`). The call is queued in Control, the client
   picks it up on its next check-in, and the Gateway polls for the response.
4. **Approval required.** The policy decision is ASK, so nothing runs.

The call is bounded by the Gateway request timeout and the credential expiry. A
retry with the same JSON-RPC `id` and the same caller maps to the same invocation.
A finished Tool Host result is returned again, not re-executed.

```http
POST /mcp HTTP/1.1
Mcp-Method: tools/call
Mcp-Name: files.read
(other headers as above)

{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "files.read",
    "arguments": {"path": "workspace/readme.txt"},
    "_meta": {"io.modelcontextprotocol/protocolVersion": "2026-07-28", "io.modelcontextprotocol/clientCapabilities": {}}
  }
}
```

Tool Host result (paths 1 and 2):

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "result": {
    "content": [{"type": "text", "text": "# Project readme\n..."}],
    "isError": false,
    "structuredContent": {"path": "workspace/readme.txt", "bytes": 1824},
    "resultType": "complete",
    "_meta": {"io.modelcontextprotocol/serverInfo": {"name": "olo-toolgate-gateway", "version": "1.0.0-dev.1"}}
  }
}
```

Client device relay result (path 3). The tool output is returned both as
`structuredContent` and as JSON text:

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "result": {
    "content": [{"type": "text", "text": "{\"path\":\"workspace/readme.txt\",\"bytes\":1824}"}],
    "structuredContent": {"path": "workspace/readme.txt", "bytes": 1824},
    "isError": false,
    "resultType": "complete",
    "_meta": {"io.modelcontextprotocol/serverInfo": {"name": "olo-toolgate-gateway", "version": "1.0.0-dev.1"}}
  }
}
```

Approval required (path 4). This is a normal JSON-RPC result with `isError: true`.
An administrator decides it in Control ([section 9](#9-control-access-invocations-and-approvals)).

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "result": {
    "content": [{"type": "text", "text": "Approval required"}],
    "structuredContent": {"invocationId": "inv-6c1f0e…", "state": "PENDING_APPROVAL"},
    "isError": true,
    "resultType": "complete",
    "_meta": {"io.modelcontextprotocol/serverInfo": {"name": "olo-toolgate-gateway", "version": "1.0.0-dev.1"}}
  }
}
```

### MCP error responses

Errors raised after the JSON-RPC frame is parsed are JSON-RPC errors. They echo
the request `id`, except for parse and invalid-frame errors, which use
`"id": null`.

```json
{"jsonrpc": "2.0", "id": 3, "error": {"code": -32602, "message": "Tool unavailable for this actor and device", "data": null}}
```

| HTTP | JSON-RPC code | `message` | Cause |
|---|---|---|---|
| 400 | -32700 | `Parse error` | Body is not strict JSON |
| 400 | -32600 | `Invalid request` | Wrong `jsonrpc`, bad `id`, unknown top-level keys, or `params` is not an object |
| 406 | -32600 | `JSON and SSE acceptance required` | `Accept` is missing a required media type |
| 400 | -32020 | `Header mismatch` | `MCP-Protocol-Version`, `Mcp-Method` or `Mcp-Name` does not match the body |
| 400 | -32022 | `Unsupported protocol version` | `initialize`, or another version, with no `_meta` version. `data = {"supported": ["2026-07-28"], "requested": "<value>"}` |
| 400 | -32602 | `Client capabilities required` | `_meta` client capabilities missing |
| 400 | -32602 | `Invalid parameters` | Extra keys in `tools/call` params, or bad `arguments` |
| 403 | -32602 | `Tool unavailable for this actor and device` | Tool not in the caller's enabled catalog |
| 404 | -32601 | `Method not found` | Any method other than the three above |
| varies | -32000 | `Authorization failed`, `Tool permit refused`, `Local tool request rejected`, `Local tool response unavailable`, `Tool Host unavailable` | Control or executor refused. `data.code` holds the `ErrorCode`. |
| 200 | -32000 | `Tool Host refused the call`, `Tool execution failed`, `Local tool execution failed` | The executor ran or refused the call. `data` holds `code`, `requestId` and the `reason`, `outcome` or `error`. |

Some failures return a plain HTTP `ErrorEnvelope` instead of a JSON-RPC error:

- authentication (`401`), tenant/host checks, rate limits and body/header limits
- a JSON-RPC notification (a request without `id`): `501 UNSUPPORTED`
- an invocation in a state that cannot run (`403 FORBIDDEN`)
- a deadline that is too short or has passed (`504 TIMEOUT`)

---

## 4. Gateway management API

Served on `managementListen` only (default `:9091`). No authentication. Keep it on
a private network.

| Method | Path | Response |
|---|---|---|
| GET | `/v1/health/live` | `200` while the process serves |
| GET | `/v1/health/ready` | `200` when Control authority was seen in the last 5 s, credentials are valid and the Gateway is not draining. `503` otherwise. |
| GET | `/v1/credentials` | Digest and count of the loaded credential file |
| GET | `/v1/metrics` | Prometheus text (`text/plain; version=0.0.4`) |

```http
GET /v1/credentials HTTP/1.1
Host: 127.0.0.1:9091
```

```json
{"sha256": "0d5e…64 hex…", "count": 3}
```

To reload credentials, replace the credentials file atomically and send `SIGHUP`.
The Gateway validates the whole file before swapping. If anything is invalid, it
keeps the previous set. Compare `/v1/credentials` before and after to confirm the
reload.

---

## 5. Control: public and discovery routes

No authentication. All routes go through tenant host routing.

| Method | Path | Purpose |
|---|---|---|
| GET | `/` | `303` redirect to `/console/` |
| GET | `/.well-known/olo-toolgate-client` | Signed discovery (`SignedClientDiscovery`). Direct TLS required. |
| GET | `/api/public/v1/clients` | Client download manifest (`ClientDownloadManifest`) |
| GET | `/api/public/v1/installers` | Installer manifest (`ClientInstallerManifest`) |
| GET | `/api/public/v1/clients/extension` | Browser-extension download metadata |
| GET | `/api/public/v1/clients/configuration` | `{"serverUrl": "<canonical Control URL>"}` |
| GET | `/api/public/v1/clients/local-trust` | Quickstart only: `{"serverUrl", "caCertificatePem"}` |
| GET | `/api/public/v1/clients/setup/{target}` | Windows installer preconfigured for this server |
| GET | `/api/public/v1/clients/{filename}` | Client binary download |

Example:

```http
GET /api/public/v1/clients/configuration HTTP/1.1
Host: control.example.com
```

```json
{"serverUrl": "https://control.example.com"}
```

---

## 6. Control: console session, settings and administration

Base path `/api/control/v1`. Console bearer JWT required.

| Method | Path | Purpose |
|---|---|---|
| GET | `/admin-session` | Caller's role, permissions and tenant |
| GET | `/openapi` | The served Control OpenAPI document |
| GET | `/settings?section=` | Server settings (Super Admin) |
| PUT | `/settings` | Replace settings (Super Admin) |
| PATCH | `/settings` | Merge-patch settings (`application/merge-patch+json`, Super Admin) |
| GET | `/review-policy` | `{"requiredReviews": n}`, for any signed-in user |
| POST | `/users/{id}/enable` | Enable a user (Super Admin) |
| POST | `/users/{id}/disable` | Disable a user and end its sessions and delegated tokens (Super Admin) |
| POST | `/workload-bindings/{id}/credential` | Rotate a workload credential (Super Admin) |

### GET /admin-session

```http
GET /api/control/v1/admin-session HTTP/1.1
Host: control.example.com
Authorization: Bearer <console JWT>
```

```json
{
  "role": "SUPER_ADMIN",
  "permissions": ["user.read", "agent.read", "device.read", "device.enroll", "server_configuration.read"],
  "bootstrapAdminUserId": "admin",
  "tenantId": "10000000-0000-4000-8000-000000000001"
}
```

### PATCH /settings

```http
PATCH /api/control/v1/settings HTTP/1.1
Authorization: Bearer <console JWT>
Content-Type: application/merge-patch+json
If-Match: 4
Idempotency-Key: settings-2026-10-22-1

{"deviceApproval": {"autoApprove": false}, "configuration": {"requiredReviews": 1}}
```

Response `200` (`ControlSettingsView`). When reviews are required, a threshold
change instead returns `202` with a `SETTINGS` configuration change.

```json
{"formatVersion": 2, "revision": 5, "gateway": {"gatewayName": "Primary"}, "deviceApproval": {"autoApprove": false}, "configuration": {"requiredReviews": 1}}
```

### POST /workload-bindings/{id}/credential

Registers the SHA-256 of a new agent token. The response includes the
`RequestContext` to put in the Gateway credentials file.

```http
POST /api/control/v1/workload-bindings/wb-support-bot/credential HTTP/1.1
Authorization: Bearer <console JWT>
Content-Type: application/json
Idempotency-Key: rotate-wb-support-bot-3

{"credentialSha256": "c0ffee…64 hex…", "enable": true}
```

```json
{
  "binding": {
    "id": "wb-support-bot", "name": "Support bot", "enabled": true, "revision": 4,
    "agentId": "agent-support-bot", "mode": "SERVICE",
    "issuer": "toolgate", "subject": "agent-support-bot", "audience": "gateway",
    "credentialSha256": "c0ffee…64 hex…", "credentialEpoch": 3, "expiresAtUnixMs": 1800576000000
  },
  "context": {
    "requestId": "gw", "tenantId": "10000000-0000-4000-8000-000000000001",
    "mode": "SERVICE", "credentialKind": "STATIC_TOKEN",
    "agentId": "agent-support-bot", "workloadBindingId": "wb-support-bot",
    "chain": [{"agentId": "agent-support-bot", "workloadBindingId": "wb-support-bot"}],
    "credentialEpoch": 3, "bindingId": "exec-default"
  }
}
```

---

## 7. Control: directory collections

Base path `/api/control/v1`. Console bearer JWT required. These generic routes
serve every directory kind:

`users`, `teams`, `agents`, `tools`, `policies`, `devices`, `roles`,
`device-groups`, `agent-groups`, `tool-groups`, `grants`, `delegations`,
`agent-delegations`, `bindings`, `extractors`, `workload-bindings`,
`identity-bindings`, `device-evidence`.

| Method | Path | Purpose |
|---|---|---|
| GET | `/{kind}?cursor=&limit=50` | Page of records: `{"items": [...], "nextCursor": "..."}` |
| GET | `/{kind}/{id}` | One record |
| POST | `/{kind}` | Create (`Idempotency-Key`). `201` with the record, or `202` with a configuration change when reviews are required. |
| PUT | `/{kind}/{id}` | Update (`If-Match: <revision>`, `Idempotency-Key`). `200` or `202`. |
| DELETE | `/{kind}/{id}` | Delete (`If-Match`, `Idempotency-Key`) |
| GET | `/{users\|agents\|tools\|devices}/{id}/groups` | Group membership (`GroupMembership`) |
| PUT | `/{users\|agents\|tools\|devices}/{id}/groups` | Replace membership atomically |
| GET | `/{users\|agents\|tools\|devices}/{id}/effective-access` | Resolved memberships, grants, bindings and roles |

### Create an agent

```http
POST /api/control/v1/agents HTTP/1.1
Authorization: Bearer <console JWT>
Content-Type: application/json
Idempotency-Key: create-agent-support-bot

{"id": "agent-support-bot", "name": "Support bot", "enabled": true, "revision": 1, "ownerUserId": "user-alice"}
```

```json
{"id": "agent-support-bot", "name": "Support bot", "enabled": true, "revision": 1, "ownerUserId": "user-alice"}
```

### Create a tool group and a grant

```json
{"id": "tg-files", "name": "File tools", "enabled": true, "revision": 1, "toolIds": ["files.read", "files.list"]}
```

```json
{
  "id": "grant-support-files",
  "name": "Support agents may read files",
  "enabled": true,
  "revision": 1,
  "sourceType": "AGENT_GROUP",
  "sourceId": "ag-support",
  "purpose": "CAPABILITY",
  "scope": {
    "toolGroups": {"ids": ["tg-files"], "all": false},
    "deviceGroups": {"ids": ["dg-file-servers"], "all": false},
    "actions": ["read"],
    "allActions": false,
    "resources": [],
    "conditions": {
      "notBeforeUnixMs": 0, "expiresAtUnixMs": 0, "networkCidrs": [], "devicePosture": [],
      "regions": [], "hoursUtc": [], "requireOnline": true, "highRisk": false
    }
  }
}
```

### Create an execution binding

This binds a tool group to the device group that runs it.

```json
{
  "id": "exec-files",
  "name": "File tools run on file servers",
  "enabled": true,
  "revision": 1,
  "toolGroupId": "tg-files",
  "deviceGroupId": "dg-file-servers",
  "actions": ["read"],
  "allowedPackageDigests": ["9a7c…64 hex…"],
  "requireOnline": true,
  "ownerDependency": false
}
```

### Update a record with a revision guard

```http
PUT /api/control/v1/agents/agent-support-bot HTTP/1.1
Authorization: Bearer <console JWT>
Content-Type: application/json
If-Match: 1
Idempotency-Key: update-agent-support-bot-2

{"id": "agent-support-bot", "name": "Support bot (EU)", "enabled": true, "revision": 1, "ownerUserId": "user-alice"}
```

`200` returns the record with `revision: 2`. A stale `If-Match` returns
`409 CONFLICT`.

### Replace group membership

```http
PUT /api/control/v1/agents/agent-support-bot/groups HTTP/1.1
Content-Type: application/json
If-Match: 3
Idempotency-Key: agent-groups-support-bot-4

{"entityType": "AGENT", "entityId": "agent-support-bot", "groupIds": ["ag-default", "ag-support"], "revision": 3}
```

### GET effective access

```json
{
  "entityId": "agent-support-bot",
  "kind": "AGENT",
  "directoryRevision": 42,
  "authorizationEpoch": 7,
  "memberships": [{"groupType": "AGENT_GROUP", "groupId": "ag-support", "enabled": true, "roleIds": [], "grants": [{"id": "grant-support-files", "name": "Support agents may read files", "enabled": true, "revision": 1, "sourceType": "AGENT_GROUP", "sourceId": "ag-support", "purpose": "CAPABILITY", "scope": {"…": "…"}}]}],
  "bindings": [{"id": "exec-files", "toolGroupId": "tg-files", "deviceGroupId": "dg-file-servers", "…": "…"}],
  "managementRoles": []
}
```

---

## 8. Control: configuration review, import/export, audit and bundles

| Method | Path | Purpose |
|---|---|---|
| GET | `/configuration-changes?after=` | Page of reviewed configuration changes |
| GET | `/configuration-changes/{id}` | One change, with its impact and reviews |
| POST | `/configuration-changes/{id}/transition` | `SUBMIT`, approve, reject or apply a change |
| GET | `/config/export` | Full snapshot (`application/json` or `application/yaml`) |
| POST | `/config/import` | Import a snapshot, with dry-run support |
| GET | `/audit?cursor=0&limit=50` | Audit log page |
| GET | `/bundles/current` | Current signed policy bundle |
| GET | `/bundles/versions/{sequence}` | A specific bundle version |
| POST | `/bundles/publish` | Publish a new signed bundle |
| POST | `/bundles/rollback` | Re-publish an earlier bundle |

### Transition a configuration change

```http
POST /api/control/v1/configuration-changes/chg-0192/transition HTTP/1.1
Authorization: Bearer <console JWT>
Content-Type: application/json
Idempotency-Key: submit-change-one

{"expectedRevision": 1, "action": "SUBMIT"}
```

```json
{
  "id": "chg-0192",
  "requesterUserId": "user-alice",
  "command": {"operation": "CREATE", "kind": "AGENT", "entityId": "agent-support-bot", "document": "{…}", "expectedRevision": 0},
  "state": "PENDING",
  "revision": 2,
  "requiredReviews": 1,
  "impact": [{"kind": "AGENT", "entityId": "agent-support-bot", "operation": "CREATE", "afterDigest": "…"}],
  "affectedGroups": ["ag-support"],
  "affectedIndividuals": [],
  "reviews": [],
  "…": "directoryRevision, authorizationEpoch, createdAtUnixMs, expiresAtUnixMs"
}
```

### Import a snapshot (dry run)

```http
POST /api/control/v1/config/import HTTP/1.1
Authorization: Bearer <console JWT>
Content-Type: application/json
Idempotency-Key: import-2026-10-22

{"snapshot": {"formatVersion": 2, "tenantId": "10000000-0000-4000-8000-000000000001", "revision": 0, "users": [], "agents": [], "…": "…"}, "mode": "MERGE", "dryRun": true}
```

```json
{"applied": false, "revision": 42, "changes": [{"kind": "AGENT", "id": "agent-support-bot", "operation": "CREATE"}]}
```

See [import/export](../control-plane/import-export.md) for modes and identity
resolution.

### Audit page

```json
{"items": [{"sequence": 981, "tenantId": "10000000-0000-4000-8000-000000000001", "actorId": "4be1…64 hex…", "operation": "UPDATE", "target": "agents/agent-support-bot", "revision": 2, "requestId": "req-01", "occurredAt": "2026-10-22T10:00:00Z", "requestDigest": "…"}], "nextCursor": "982"}
```

---

## 9. Control: access, invocations and approvals

Base path `/api/control/v1`.

### Gateway service routes

These routes require the `toolgate-relay-gateway` service JWT. Normally only the
Gateway calls them.

| Method | Path | Body → Response |
|---|---|---|
| GET | `/access/health` | Authority liveness polled by Gateway readiness |
| POST | `/access/invocations` | `EnterpriseInvocationRequest` → `EnterpriseInvocation` |
| POST | `/access/invocations/reserve` | `EnterpriseReservationRequest` or `PermitReservationRequest` → reservation with a signed permit |
| POST | `/access/dispatches` | `Dispatch` → `ToolHostDispatchStatus` (pull-mode hand-off) |
| GET | `/access/dispatches/{id}` | `ToolHostDispatchStatus` |

```json
// POST /api/control/v1/access/invocations/reserve  (Tool Host permit)
{"invocationId": "inv-6c1f0e…", "audience": "device-host-a", "attempt": 1, "arguments": {"path": "workspace/readme.txt"}}
```

```json
// GET /api/control/v1/access/dispatches/inv-6c1f0e…
{"invocationId": "inv-6c1f0e…", "hostId": "device-host-a", "state": "FINISHED",
 "admission": {"kind": "ADMITTED", "invocationId": "inv-6c1f0e…"},
 "result": {"invocationId": "inv-6c1f0e…", "outcome": "COMPLETED", "result": {"kind": "RESULT", "resultType": "complete", "content": [{"type": "text", "text": "…"}], "isError": false}}}
```

### Device and executor routes

These routes need a device certificate.

| Method | Path | Purpose |
|---|---|---|
| POST | `/access/permits/consume` | Executor consumes a permit just before the effect (`EnterprisePermitConsumption` → `EnterpriseInvocation`) |
| POST | `/access/effects/report` | Executor reports the outcome (`EnterpriseEffectReport`) |
| GET | `/access/invocations/{id}/device-outcome` | Executor reads the recorded outcome |
| POST | `/access/invocations/{id}/events` | Executor streams run events (`RunEventBatch` → `RunEventAck`) |
| POST | `/access/secrets/deliver` | Executor receives a vault secret bound to a permit |

```json
// POST /api/control/v1/access/invocations/inv-6c1f0e…/events
{"invocationId": "inv-6c1f0e…", "fencingToken": "ft-01", "attempt": 1, "events": [{"sequence": 1, "emittedAtUnixMs": 1792800000100, "kind": "LOG"}], "droppedFrames": 0}
```

```json
{"invocationId": "inv-6c1f0e…", "attempt": 1, "acceptedThrough": 1, "duplicates": 0, "refused": 0}
```

### Console routes

These routes need the console JWT.

| Method | Path | Purpose |
|---|---|---|
| GET | `/access/invocations?after=&limit=50` | Invocation history |
| GET | `/access/invocations/{id}` | One invocation |
| POST | `/access/invocations/{id}/cancel` | Cancel an invocation that has not run |
| GET | `/access/invocations/{id}/events?after=0&limit=200` | Run events for one invocation |
| POST | `/access/invocations/{id}/reconciliation` | Propose an outcome for an `OUTCOME_UNKNOWN` invocation |
| GET | `/access/reconciliations/{id}` | One reconciliation |
| POST | `/access/reconciliations/{id}/decision` | Approve or reject a reconciliation |
| POST | `/access/simulate` | Evaluate an `EnterpriseEvaluation` without side effects → `EnterpriseDecision` |
| POST | `/access/shadow` | Compare a candidate configuration with the live one |
| GET | `/access/status` | Directory revision, epoch and device adoption |
| GET | `/access/operations` | Operational status counters |
| POST | `/access/human/catalog` | Tools a signed-in human may run on a device |
| POST | `/access/human/invocations` | Run a tool as a human (`200` or `202`) |
| GET | `/access/human/invocations/{id}/result` | Result of a human run |
| GET | `/approvals?cursor=&limit=50` | Pending and decided approvals |
| GET | `/approvals/{id}` | One approval |
| POST | `/approvals/{id}/decision` | Approve or deny one obligation |

#### Approve an ASK invocation

```http
POST /api/control/v1/approvals/apr-77/decision HTTP/1.1
Authorization: Bearer <console JWT>
Content-Type: application/json
Idempotency-Key: apr-77-approve

{"expectedRevision": 1, "obligationId": "obl-manager", "decision": "APPROVE"}
```

```json
{
  "id": "apr-77", "approvalType": "OPERATION", "invocationId": "inv-6c1f0e…",
  "requestDigest": "b5d4…", "authorizationEpoch": 7, "directoryRevision": 42,
  "obligationIds": ["obl-manager"],
  "reviews": [{"obligationId": "obl-manager", "reviewerUserId": "user-bob", "decision": "APPROVE", "decidedAtUnixMs": 1792800030000}],
  "state": "APPROVED", "revision": 2, "expiresAtUnixMs": 1792800300000
}
```

#### Run a tool as a human

```http
POST /api/control/v1/access/human/invocations HTTP/1.1
Authorization: Bearer <console JWT>
Content-Type: application/json
Idempotency-Key: human-run-01

{"bindingId": "exec-files", "deviceId": "device-host-a", "request": {"toolId": "files.read", "action": "read", "arguments": {"path": "workspace/readme.txt"}}}
```

`200` or `202` (`EnterpriseHumanOutcome`):

```json
{"invocation": {"id": "inv-91aa…", "state": "QUEUED", "…": "…"}, "dispatch": {"record": {"requestId": "inv-91aa…", "deviceId": "device-host-a", "toolId": "files.read", "state": "RECEIVED", "receivedAtUnixMs": 1792800000000, "expiresAtUnixMs": 1792800030000}}}
```

Then poll `GET /access/human/invocations/inv-91aa…/result` (`RemoteToolResponse`)
until `record.state` is `DONE`, `FAILED` or `EXPIRED`.

#### Simulate a decision

```json
// POST /api/control/v1/access/simulate
{
  "context": {"requestId": "sim-1", "tenantId": "10000000-0000-4000-8000-000000000001", "mode": "SERVICE", "agentId": "agent-support-bot", "workloadBindingId": "wb-support-bot", "chain": [{"agentId": "agent-support-bot", "workloadBindingId": "wb-support-bot"}], "bindingId": "exec-files", "deviceId": "device-host-a"},
  "toolId": "files.read", "action": "read", "argumentsDigest": "a1c2…", "resources": [{"kind": "FILE", "locator": "workspace/readme.txt"}],
  "toolDigest": "3f1e…", "packageDigest": "9a7c…", "nowUnixMs": 1792800000000, "authorityRevision": 42, "online": true
}
```

```json
{"decision": "ALLOW", "reason": "MATCHED", "witnesses": [], "obligations": [], "revision": 42, "authorizationEpoch": 7, "validUntilUnixMs": 1792800010000, "diagnosticId": "diag-1"}
```

---

## 10. Control: endpoint devices and enrollment

Base path `/api/control/v1/endpoint`.

| Method | Path | Caller | Purpose |
|---|---|---|---|
| POST | `/ping` | Client (before or after enrollment) | Presence. Returns `EndpointConnectionDirective` (`ENROLL`, `CHECK_IN`, `WAIT`…). |
| POST | `/enrollments` | Client | Start enrollment with a CSR. Returns user and device codes. |
| POST | `/enrollments/request-approval` | Client | Start enrollment and request admin approval |
| POST | `/enrollments/poll` | Client | Poll for the issued certificate |
| GET | `/enrollments` | Console | Pending enrollments |
| POST | `/enrollments/lookup` | Console | Look up a pending code (`{"userCode"}`) |
| GET | `/enrollments/review?code=` | Console | Review details for a code |
| POST | `/enrollments/decision` | Console | Approve or deny an enrollment |
| POST | `/console-device/challenge` | Console | Start proof that the console runs on an enrolled device |
| POST | `/console-device/verify` | Console | Finish that proof |
| POST | `/check-in` | Device (mTLS) | Report installed tools and state. Receives permissions and jobs. |
| GET | `/socket` | Device (mTLS) | WebSocket upgrade for the direct job channel |
| GET | `/devices` | Console | Combined device registry view |
| GET | `/devices/{id}` | Console | One device record |
| POST | `/devices/{id}/approval` | Console (admin) | Approve or deapprove the connection, timed or unlimited |
| POST | `/devices/{id}/enabled` | Console (admin) | Enable or disable the directory device |
| POST | `/devices/{id}/revoke` | Console (admin) | Permanently revoke the device key |
| POST | `/devices/{id}/request-approval` | Console | Ask an approver to approve the device |

### Enrollment flow

```json
// 1. Client: POST /api/control/v1/endpoint/enrollments
{"deviceId": "device-laptop-42", "clientVersion": "0.10.0", "platform": "WINDOWS", "csrPem": "-----BEGIN CERTIFICATE REQUEST-----…", "capabilities": ["endpoint.identity.v1", "endpoint.check-in.v1"]}
```

```json
{"enrollmentId": "enr-5d2", "deviceCode": "<opaque>", "userCode": "WXYZ-1234", "verificationUri": "https://control.example.com/console/#enroll", "expiresAtUnixMs": 1792800600000, "pollIntervalSeconds": 2}
```

```json
// 2. Admin: POST /api/control/v1/endpoint/enrollments/lookup
{"userCode": "WXYZ-1234"}
```

```json
{"enrollmentId": "enr-5d2", "deviceId": "device-laptop-42", "platform": "WINDOWS", "keyFingerprint": "SHA256:Qm9i…", "expiresAtUnixMs": 1792800600000}
```

```json
// 3. Admin: POST /api/control/v1/endpoint/enrollments/decision
{"userCode": "WXYZ-1234", "keyFingerprint": "SHA256:Qm9i…", "choice": "APPROVE", "connectionExpiresAtUnixMs": 1795392000000}
```

```json
// 4. Client: POST /api/control/v1/endpoint/enrollments/poll
{"enrollmentId": "enr-5d2", "deviceCode": "<opaque>"}
```

Five failed lookups or decisions per user in ten minutes return
`429 {"error": "RATE_LIMITED"}`.

### Approve a device connection

```http
POST /api/control/v1/endpoint/devices/device-laptop-42/approval HTTP/1.1
Authorization: Bearer <console JWT>
Content-Type: application/json
Idempotency-Key: approve-laptop-42

{"expectedApprovalRevision": 1, "approved": true, "unlimitedConnection": true}
```

```json
{"deviceId": "device-laptop-42", "tenantId": "10000000-0000-4000-8000-000000000001", "userId": "user-alice", "state": "ACTIVE", "revision": 9, "lastSeenUnixMs": 1792800000000, "reportSequence": 31, "connectionApproved": true, "approvalRevision": 2}
```

Set either `connectionExpiresAtUnixMs` or `unlimitedConnection: true`.
`/devices/{id}/enabled` takes `{"expectedRevision": n, "enabled": false}`, and
`/devices/{id}/revoke` takes `{"expectedRevision": n}`. A suspended client
receives `423` and should retry later.

### Check-in

```json
// POST /api/control/v1/endpoint/check-in  (device mTLS)
{"sequence": 32, "report": {"deviceId": "device-laptop-42", "clientVersion": "0.10.0", "appliedRevision": 12, "packages": [{"packageId": "files", "version": "1.2.0", "state": "READY"}]}, "localTools": [{"toolId": "files.read", "action": "read", "description": "Read a text file", "enabled": true, "inputSchema": {"type": "object"}, "toolDigest": "3f1e…", "packageDigest": "9a7c…"}]}
```

The reply (`EndpointCheckInAck`) carries `nextIntervalMs`, any permission
replacement and any pending relay jobs. See
[device registry](../control-plane/device-registry.md) and
[client relay](../client/server-mcp.md).

---

## 11. Control: MCP client relay

The Gateway uses these routes to run `tools/call` on enrolled client devices
(path 3 in [section 3](#toolscall)). Base path `/api/control/v1/mcp`.

| Method | Path | Caller | Body → Response |
|---|---|---|---|
| POST | `/catalog` | Gateway service JWT | `RequestContext` → `LocalToolCatalog` |
| POST | `/requests` | Gateway service JWT | `RemoteToolSubmission` → `RemoteToolResponse` |
| POST | `/responses` | Gateway service JWT | `RequestContext` → `RemoteToolResponse` |
| POST | `/authorize` | Device mTLS | `RemoteToolAuthorization` → `RemoteToolAuthorizationAck` |
| POST | `/results` | Device mTLS | `RemoteToolResult` → `RemoteToolRecord` |
| GET | `/requests` | Console | Recent relay requests (`RemoteToolPage`) |
| GET | `/requests/{id}` | Console | One request with its completed output |

### Flow

```json
// Gateway → POST /api/control/v1/mcp/requests
{
  "context": {"requestId": "inv-6c1f0e…", "tenantId": "10000000-0000-4000-8000-000000000001", "mode": "SERVICE", "credentialKind": "STATIC_TOKEN", "agentId": "agent-support-bot", "workloadBindingId": "wb-support-bot", "chain": [{"agentId": "agent-support-bot", "workloadBindingId": "wb-support-bot"}]},
  "invocationId": "inv-6c1f0e…",
  "request": {"toolId": "files.read", "action": "read", "arguments": {"path": "workspace/readme.txt"}},
  "expiresAtUnixMs": 1792800019000
}
```

```json
{"record": {"requestId": "inv-6c1f0e…", "deviceId": "device-laptop-42", "agentId": "agent-support-bot", "toolId": "files.read", "state": "RECEIVED", "receivedAtUnixMs": 1792800000000, "expiresAtUnixMs": 1792800019000}}
```

The client receives the job on its next check-in or socket message. It then
authorizes the job immediately before the effect:

```json
// Device → POST /api/control/v1/mcp/authorize
{"requestId": "inv-6c1f0e…", "leaseId": "lease-a1", "request": {"toolId": "files.read", "action": "read", "arguments": {"path": "workspace/readme.txt"}}}
```

```json
{"expiresAtUnixMs": 1792800019000}
```

```json
// Device → POST /api/control/v1/mcp/results
{"requestId": "inv-6c1f0e…", "leaseId": "lease-a1", "output": {"path": "workspace/readme.txt", "bytes": 1824}}
```

The Gateway polls `POST /mcp/responses` with the same context every 250 ms until
`record.state` is `DONE`, `FAILED` or `EXPIRED`:

```json
{"record": {"requestId": "inv-6c1f0e…", "deviceId": "device-laptop-42", "toolId": "files.read", "state": "DONE", "receivedAtUnixMs": 1792800000000, "expiresAtUnixMs": 1792800019000, "completedAtUnixMs": 1792800002400}, "result": {"requestId": "inv-6c1f0e…", "leaseId": "lease-a1", "output": {"path": "workspace/readme.txt", "bytes": 1824}}}
```

---

## 12. Control: packages, Tool Hosts and provisioning

Base path `/api/control/v1`.

### Packages (console)

| Method | Path | Purpose |
|---|---|---|
| POST | `/packages/uploads` | Upload a signed package (`application/vnd.ololabs.toolgate.package+zip`, at most 64 MiB). `201` for a new package, `200` if it already exists. |
| GET | `/packages` | Uploaded package versions |
| GET | `/packages/deployments` | Server deployments |
| POST | `/packages/deployments` | Deploy a package to Tool Hosts |
| POST | `/packages/client-releases` | Build a client-tool image for a Java or Python package and issue a fleet release |
| GET | `/packages/provisioning` | Provisioning status for every deployment profile |
| GET | `/packages/provisioning/{profileId}` | One profile's status |

```bash
curl -sS -X POST https://control.example.com/api/control/v1/packages/uploads \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/vnd.ololabs.toolgate.package+zip" \
  --data-binary @files-1.2.0.toolgate.zip
```

```json
{"packageId": "files", "version": "1.2.0", "packageDigest": "9a7c…", "descriptorSetDigest": "77de…", "state": "UPLOADED", "revision": 1, "uploadedAtUnixMs": 1792800000000}
```

```json
// POST /api/control/v1/packages/deployments
{"packageDigest": "9a7c…", "globals": {"rootDirectory": "/srv/workspace"}, "hostIds": ["device-host-a"]}
```

```json
{"packageId": "files", "version": "1.2.0", "packageDigest": "9a7c…", "revision": 1, "serviceProfileDigest": "…", "serviceProfileRevision": 1, "globalsDigest": "…", "toolIds": ["files.read", "files.list"], "deployedAtUnixMs": 1792800005000, "hostIds": ["device-host-a"]}
```

```json
// POST /api/control/v1/packages/client-releases
{"packageDigest": "9a7c…"}
```

### Tool Host routes (device mTLS)

| Method | Path | Purpose |
|---|---|---|
| POST | `/tool-hosts/registrations` | Host announces its pool, version and sandbox tiers |
| GET | `/tool-hosts/assignments` | Packages this host must serve |
| GET | `/tool-hosts/packages/{digest}` | Download package bytes |
| GET | `/tool-hosts/documents/{digest}` | Download a referenced document |
| POST | `/tool-hosts/dispatches/next` | Pull mode: claim the next dispatch (`Dispatch`, or `204` when none is waiting) |
| POST | `/tool-hosts/dispatches/{id}/admission` | Pull mode: admit or refuse it |
| POST | `/tool-hosts/dispatches/{id}/result` | Pull mode: post the result (at most 8 MiB) |

```json
// POST /api/control/v1/tool-hosts/registrations
{"hostId": "device-host-a", "poolId": "pool-default", "version": "0.10.0", "protocol": {"min": 1, "max": 1}, "launcherVersion": "0.10.0", "tiers": ["OCI_SANDBOX"]}
```

```json
// POST /api/control/v1/tool-hosts/dispatches/inv-6c1f0e…/admission
{"kind": "ADMITTED", "invocationId": "inv-6c1f0e…", "fencingToken": "ft-01"}
```

```json
// POST /api/control/v1/tool-hosts/dispatches/inv-6c1f0e…/result
{"invocationId": "inv-6c1f0e…", "fencingToken": "ft-01", "outcome": "COMPLETED", "result": {"kind": "RESULT", "resultType": "complete", "content": [{"type": "text", "text": "…"}], "isError": false}}
```

---

## 13. Control: marketplace, fleet, builder and vault

### Marketplace (console)

| Method | Path | Purpose |
|---|---|---|
| GET | `/marketplace` | `MarketplaceStatus`: enabled, ID, base URL and keys |
| GET | `/marketplace/catalog?page=&limit=&q=&category=&tag=&publisher=` | Catalog page (proxied from the configured marketplace) |
| GET | `/marketplace/packages/{packageId}` | Package versions |
| GET | `/marketplace/packages/{packageId}/{version}` | Version detail |
| GET | `/marketplace/publishers/{slug}` | Publisher profile |
| GET | `/marketplace/media/{mediaId}` | PNG or JPEG media, at most 1 MiB |
| POST | `/marketplace/imports` | Fetch, verify and upload a package. `201` or `200`, with `PackageVersion`. |

```json
// POST /api/control/v1/marketplace/imports
{"packageId": "files", "version": "1.2.0"}
```

See [marketplace integration](../control-plane/marketplace-integration.md) and
the [external Marketplace contract](marketplace-api.md).

### Fleet

| Method | Path | Caller | Purpose |
|---|---|---|---|
| GET/POST | `/fleet/releases?cursor=` | Console | List or publish client package releases |
| GET/POST | `/fleet/rollouts?cursor=` | Console | List or create rollouts |
| POST | `/fleet/rollouts/{id}/advance` | Console | Advance a rollout percentage |
| GET | `/fleet/desired` | Device mTLS | Signed desired state |
| POST | `/fleet/artifact-grants` | Device mTLS | Signed grant to download artifacts |
| POST | `/fleet/artifacts/download` | Device mTLS | Download an artifact with a grant |

```json
// POST /api/control/v1/fleet/rollouts
{"id": "ro-files-120", "packageId": "files", "version": "1.2.0", "deviceIds": ["device-laptop-42"], "desiredPresence": true, "percentage": 25}
```

### Tool builder

| Method | Path | Purpose |
|---|---|---|
| GET | `/builder/status` | SDK version and which groups may build |
| GET/POST | `/builder/drafts?cursor=` | List or save drafts |
| POST | `/builder/sources` | Import a source archive (`application/zip`) |
| GET/POST | `/builder/builds?cursor=` | List or start builds (`{"id", "draftId", "expectedRevision"}`) |
| GET | `/builder/builds/{id}/package` | Download the built package |
| GET | `/builder/builds/{id}/source` | Download the build's source |

See [tool builder](../client/tool-builder.md).

### Vault

| Method | Path | Purpose |
|---|---|---|
| GET | `/vault` | Secret references only, never values (`EnterpriseVaultPage`) |
| POST | `/vault` | Store a secret scoped to a tool group and device group. `201 {"stored": true}`. |

```json
// POST /api/control/v1/vault   (Idempotency-Key required)
{"name": "crm/api-key", "value": "<secret>", "toolGroupId": "tg-crm", "deviceGroupId": "dg-crm-hosts"}
```

---

## 14. Quickstart composition routes

The single-node Quickstart image puts a loopback HTTP supervisor (port `8080`) in
front of Control (`8082` HTTP and `8443` direct TLS) and Gateway (`8081`). These
routes exist only in Quickstart. The supervisor accepts only `localhost` or
`127.0.0.1` hosts.

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/api/quickstart/v1/status` | None | Mode, tenant, readiness, version, database mode |
| GET | `/api/quickstart/v1/login-hints` | None | Demo login hints, when enabled, with the shared agent token named by `TOOLGATE_LOGIN_HINT_AGENT` |
| POST | `/api/quickstart/v1/login` | None (rate-limited to 10 per minute) | Exchange a password for a 15-minute console JWT. Also sets a session cookie. |
| GET | `/api/quickstart/v1/session` | Session cookie | Re-issue the JWT from the cookie |
| POST | `/api/quickstart/v1/logout` | None | Clear the session cookie |
| GET/POST | `/api/quickstart/v1/vault` | Console JWT | Names-only list, and store (scoped to the default groups) |
| POST | `/api/quickstart/v1/workloads/{id}/reauthorize` | Console JWT | Re-issue a delegated workload token |
| POST | `/api/quickstart/v1/demo-accounts/claim` | Claim bearer secret | Server-to-server demo account claim |
| GET | `/health/ready` | None | Composition readiness |
| POST | `/mcp` | Workload bearer | Forwarded to Gateway only when `TOOLGATE_ALLOW_LOOPBACK_MCP_HTTP=true` |

Everything under `/console/`, `/api/control/v1/` and `/api/public/v1/clients*` is
forwarded to Control. On Control's direct TLS listener, the Quickstart build also
forwards `/access/invocations`, `/access/catalog` and `/mcp` to the Gateway.

```http
POST /api/quickstart/v1/login HTTP/1.1
Host: localhost:8080
Content-Type: application/json

{"username": "admin", "password": "<password>"}
```

```json
{"accessToken": "eyJhbGciOiJSUzI1NiIs…"}
```

On first login, also send `newPassword` (16–128 characters).

```json
// GET /api/quickstart/v1/status
{"mode": "Quickstart", "tenant": "10000000-0000-4000-8000-000000000001", "nonHa": true, "ready": true, "version": "1.0.0-dev.1", "passwordRequired": true, "database": "sqlite", "authority": "online"}
```

---

## 15. Control management endpoints

Served by Quarkus on the management port (`9092`). No authentication.

| Path | Purpose |
|---|---|
| `/q/health/live` | Liveness |
| `/q/health/ready` | Readiness, including the database |
| `/q/metrics` | Prometheus metrics |

---

## 16. Known gaps between contracts and implementation

These were found while building this reference (October 2026):

- **Missing from `gateway-v1.yaml`.** The served routes
  `/.well-known/olo-toolgate-client`, `/api/public/v1/clients/configuration`,
  `POST /access/catalog` and management `GET /v1/credentials` are not described.
- **Missing from `control-v1.yaml`.** The served routes
  `GET /api/public/v1/clients/extension`, `POST /api/control/v1/packages/client-releases`
  and `GET /api/control/v1/access/health` are not described.
- **Quickstart `tools` and `invoke`.** Control's Quickstart proxy forwards
  `GET /api/quickstart/v1/tools` and `POST /api/quickstart/v1/invoke`, but the
  Quickstart supervisor does not implement them. Both return `404`.
- **Lost Tool Host error metadata.** For a completed Tool Host result that carries
  a tool error, the Gateway sets `_meta["io.ololabs.toolgate/error"]`. The final
  response builder then replaces `_meta` with the server info, so MCP clients
  never see that field.
