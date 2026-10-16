<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Client tools through the Gateway

Managed tool execution uses a trusted registered device, current owner/device enablement, installed-client connection approval, and the existing agent/tool/action/resource filters before every protected effect. Registration, green availability or deployment never grants execution. Quickstart’s separate REST-forwarding record can block discovery, dispatch and result delivery without reenrolling target clients.

See [Device registry and tool-call controls](../control-plane/device-registry.md).

An enrolled client checks in with Control at the configured check-in interval while connected (2 s on Quickstart, 2 s with jitter by default elsewhere; see [client check-in](../control-plane/configuration.md#client-check-in)). Each
poll reports the tools actually installed and ready on that device. Control caches
the device's effective permissions and their digest. A change sends a complete
replacement on the next poll; Control repeats it until the client acknowledges the
digest. Disabled users, devices, teams, policies and tools narrow this configuration.
Clients keep it in their protected application state directory as `permissions.json`.
A discovery snapshot grants no execution permission.

Enable the optional Gateway relay with this administrator configuration:

```json
{
  "localMcp": {
    "url": "https://control.example.test",
    "tokenPath": "/etc/toolgate/relay-auth/access-token",
    "developmentLoopbackHttp": false
  }
}
```

The origin is fixed and redirects and environment proxies are disabled. The file
contains a Control service JWT for the same tenant, with the dedicated
`toolgate-relay-gateway` role and a lifetime of at most fifteen minutes. Arrange
credential rotation through the existing identity provider. This service identity
is separate from an agent's runtime bearer token. No agent bearer token reaches a
client, and Windows setup asks for no credentials.

Set Gateway request/connection/shutdown timeouts to 30000/35000/35000 milliseconds
to leave time for client polls and execution. Relay mode requires a request timeout
of at least ten seconds. Requests expire within thirty seconds and before their
runtime credential expires. Quickstart enables the relay automatically; an agent's
runtime credential must name an enrolled device to see client tools. Unenrolled
device bindings return an empty catalog.

Helm supports `gateway.localMcp.enabled`, `url`, `tokenSecret`, `tokenKey` and
`controlPort`. Enable signed bundle mode, provide explicit Control/DNS egress peers
and adjust the Gateway timeouts and termination grace. The token Secret is mounted
read-only and contains no runtime agent credentials. Control endpoint enrollment
and direct client mutual TLS must also be configured using the endpoint guide.

`/mcp` tools/list returns only installed definitions applicable to the authenticated
agent, user and device. Managed runtime definitions come from approved packages on
the client. Discovery without call arguments conservatively hides tools with an
applicable BLOCK. The client IPC catalog also accepts an optional `agentId`; an
unscoped query cannot expose permissions restricted to a particular agent.

An ALLOW tools/call is audited by the Gateway and queued durably in Control. The
next client poll delivers its exact request and lease. The client rechecks current
authorization with its device certificate immediately before a protected effect,
runs the signed package tool in its managed sandbox, and posts the result.
Control checks the agent scope and current permission again before returning the
result. ASK remains blocked on MCP; approvals use the existing permit flow.

The Admin dashboard and **Client tool requests** page refresh every two seconds:

1. Agent request received for the tool ID.
2. Waiting for next client poll.
3. Request submitted to client.
4. Response received.
5. Done, Failed or Expired.

The full **Client tool requests** page fills the parent workspace canvas. Its
progress list retains handoff timestamps without including arguments or outputs
in every poll. **View response** loads one completed output as escaped JSON through
`GET /api/control/v1/mcp/requests/{id}`, requiring an enabled same-tenant
administrator. Pending records show progress only. Inspection never exposes the
private client lease or incoming arguments and does not authorize execution.
Packet telemetry and audit continue to redact tool outputs and credentials.

Retries reuse the same request and client lease. The protected client
`remote-journal.json` prevents an interrupted effect from running again after a
restart; an uncertain outcome becomes a failure and completed results can be
resent. Gateway changes clear old permissions, leases, enrollment and fleet state
before enrolling against the new server. The device key is retained.

The service polls while remote jobs run. Network failure uses bounded backoff and
revocation cancels jobs. Runtime provisioning and network calls may delay a poll;
requests that miss their deadline expire. The queue is bounded to sixteen pending
requests per device and one thousand retained records per tenant. A client serves
one remote tool request at a time.

Client and Gateway transport diagnostics emit `protocol_packet` SEND/RECEIVE
messages with request correlation, poll/configuration metadata and job state.
Control emits matching messages for enrollment, check-ins and MCP relay APIs.
Credentials, certificates, enrollment/lease codes, tool arguments and outputs
are redacted. The client retains a bounded `packets.jsonl` in its protected state
directory (100 entries, at most 64 KiB), readable by device administrators; no
tool exposes it. The Windows tray offers a live packet viewer.

Quickstart exposes the same stateless `/mcp` ingress on its direct HTTPS
listener, forwarding MCP protocol headers and allowing time for the next client
poll. Only its fixed runtime proxy routes delegate opaque bearer validation to
Gateway; Control administrator JWT and client mTLS routes retain their existing
authentication.

Current clients request millisecond intervals with `X-ToolGate-Poll-Interval-Unit: milliseconds`. Control replies with `nextIntervalMs` set to the configured check-in; that value takes precedence over the legacy `nextIntervalSeconds`. Older clients receive the interval in whole seconds (at least two), and current clients fall back to seconds against an older server.

When a remote tool or builder job is delivered, the client opens a direct mTLS
WebSocket at `/api/control/v1/endpoint/socket`. Check-ins, permission replacements,
job authorization and results then share that bidirectional channel. WebSocket
ping/pong frames run every five seconds, including during execution. The client
closes the channel 30 seconds after jobs finish, then continues HTTPS polls at the check-in interval.
A dropped or unavailable channel falls back to the existing durable HTTP protocol;
lease, permission, sequence and replay checks apply to every socket message too.
Packet diagnostics show connection, ping/pong, idle disconnect and fallback states.
