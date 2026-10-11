# Gateway

Managed tool execution uses a trusted registered device, current owner/device enablement, installed-client connection approval, and the existing agent/tool/action/resource filters before every protected effect. Registration, green availability or deployment never grants execution.

See [Device registry and tool-call controls](../control-plane/device-registry.md).

The Gateway is the smallest and most security/performance-sensitive customer runtime component.

## Responsibilities

- MCP ingress/routing.
- identity validation.
- resource extraction.
- ALLOW/ASK/BLOCK.
- permit issuance.
- runtime credential brokering.
- runtime audit.
- rate limiting.

## Non-Responsibilities

- Admin UI.
- package installation.
- arbitrary custom-code execution.
- policy authoring.

Read next:

- `request-lifecycle.md`
- `policy-bundles.md`
- `execution-permits.md`

Module 01 provides authorization with static inputs and a stateless MCP skeleton.
Execution, permits, distributed bundles, approvals and credential brokering are
explicitly unsupported; their absence never becomes ALLOW.

- [Configuration](configuration.md)
- [Deployment and operations](deployment.md)
- [Upgrades and rollback](upgrades.md)
- [Performance methodology](performance.md)
- [API](../api/gateway-api.md)
- [ADR and security boundaries](../adr/002-gateway-static-foundation.md)

Current Gateway builds support signed bundles, approvals and an optional
[client MCP relay](../client/server-mcp.md). Enrolled clients receive permission
replacements and remote tool requests through their check-in polls.
