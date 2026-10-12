# Gateway configuration

Gateway uses current online Control authority for discovery, invocation creation, dispatch and result retrieval. It has no local permission policy, extractor, approval signer or offline ALLOW cache. [Group authorization](../security/enterprise-access-control.md) defines the decision model.

`TOOLGATE_GATEWAY_CONFIG` and `TOOLGATE_GATEWAY_CREDENTIALS` select administrator-mounted bounded JSON files. Unknown fields and invalid, expired or ambiguous credentials reject startup. The [online example](../examples/gateway-online.json) contains routing and capacity configuration only. Supply a Control HTTPS origin and a protected, separately refreshed service JWT file. Only explicit development fixtures may use `developmentLoopbackHttp=true` with a numeric loopback origin.

Each credential contains `tokenSha256`, a canonical `RequestContext`, and `expiresAtUnixMs`. The context binds tenant, SERVICE or DELEGATED mode, `credentialKind: "STATIC_TOKEN"`, Agent, workload binding, credential epoch/digest and execution binding; delegated mode also binds verified User/session and chain. The context names no device: Control places each call on one online device of the execution binding's device group (the caller's own device first, otherwise the least-loaded member, so a pool of executor containers shares the work). A context `expiresAtUnixMs`, if present, must equal the credential's; the Gateway fills it in. Use independently generated high-entropy tokens. Mounted facts authenticate the caller; the current enabled workload binding and complete group grants in Control determine access. A device approval creates no agent credential. Configure workloads through reviewed administration before calling tools.

`toolHosts` lists the Tool Hosts this Gateway dispatches to, as `{hostId, url}`: the host's enrolled device id and its loopback dispatch origin (at most 64). When Control places a call on one of them, the Gateway asks Control for a signed permit for that host (`POST /api/control/v1/access/invocations/reserve` with `audience`, `attempt` and the exact `arguments`), posts the permit and arguments to the host's `/v1/dispatch` and returns the host's result. A call placed on any other device, or one Control answers 409 for, goes through the device relay as before. See [Tool Host](../../apps/tool-host/README.md).

Runtime binds loopback unless `trustedTlsProxy=true`. This flag asserts a deployment boundary: use trusted TLS termination and restricted network peers. Forwarded headers never establish caller identity. Management probes and metrics have a separate restricted listener. CORS accepts only configured exact HTTPS origins.

| Limit | Default |
|---|---|
| Request body / headers | 64 KiB / 8 KiB |
| Concurrent requests / connections | 128 / 256 per replica |
| Ingress rate | 1,000 per second per replica |
| Request / connection deadline | 20 / 35 seconds |
| Shutdown drain | 35 seconds |
| Audit queue | 256 entries |

Ingress capacity limits are independent of durable group execution budgets. Control evaluates current authority immediately before protected effects. Permits last at most ten seconds, have zero clock grace, are purpose-bound and single-use. Cached discovery creates no execution right. Readiness polls authenticated current Core authority every five seconds; an outage removes readiness and blocks new effects.

See the [operator runbook](../enterprise-access-control/operations.md), [MCP API](../api/gateway-api.md), and [deployment guide](deployment.md).
