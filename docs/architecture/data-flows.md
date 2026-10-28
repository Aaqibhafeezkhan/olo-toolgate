# Core data flows

## Tool execution

Caller -> Gateway authentication -> Control current-authority decision -> ASK
review if required -> Control permit -> selected registered executor -> online
permit consumption and effect checks -> result report -> authorized result access.

Identity, enabled membership, complete grants, target binding, resource checks and
freshness remain required throughout. A failed dependency cannot create ALLOW.
Unknown external outcomes are fenced rather than blindly retried.

## Device enrollment

Client key/CSR -> pending request -> owner/fingerprint review -> connection approval
-> bound certificate -> check-in. Enablement, approval and tool permission remain
separate. See [device registry](../control-plane/device-registry.md).

## Managed package deployment

Reviewed release -> signed fleet generation -> assigned endpoint -> verify digest
and signature -> stage isolated runtime -> health check -> activate -> report state.
See [package deployment](../client/package-deployment.md).

## Server Java tools

SDK build -> CLI package -> Control upload/review/deployment -> host assignment and
verified content -> Gateway direct dispatch or Control queue -> Tool Host admission
and execution -> Control result report. See [Tool Host](../../apps/tool-host/README.md).

## Configuration changes

Scoped administrator draft -> impact/validation -> independent review -> revision
check and atomic apply -> audit/outbox. Super Admin's documented exception permits
immediate audited management changes. Neither path manufactures runtime grants.
See [configuration transfer](../control-plane/import-export.md).

See [Marketplace integration](../control-plane/marketplace-integration.md) and
[existing MCP servers](../gateway/mcp-proxy.md) for their supported flows and limits.
