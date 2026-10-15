# Gateway

Gateway authenticates MCP callers, obtains current authorization from Control and
routes calls to registered executors. It handles protocol, request bounds and
runtime observability. It does not own the database, evaluate an offline policy
snapshot, sign effect permits or execute arbitrary tool code.

Protected effects require Control-issued permits and current identity, group,
device and resource checks. Metadata caches and green device status create no
authority. Unavailable Control fails closed.

- [Configuration](configuration.md) and [deployment](deployment.md)
- [Request lifecycle](../architecture/data-flows.md) and [execution permits](../control-plane/approvals.md)
- [Policy metadata](../control-plane/policy-bundles.md) and [resource extraction](../security/enterprise-access-control.md)
- [API](../api/gateway-api.md), [client relay](../client/server-mcp.md) and [Tool Host](../../apps/tool-host/README.md)
- [Observability](../enterprise-access-control/operations.md), [performance](performance.md) and [upgrades](upgrades.md)
