# APIs and contracts

Use the checked-in [OpenAPI contracts](../../packages/contracts/openapi/README.md)
and [schemas](../../packages/contracts/schemas/README.md) for exact fields and paths.

- [HTTP and MCP API reference](api-reference.md) — every Gateway and Control route with examples
- [Gateway API](gateway-api.md)
- [Control Plane API](control-plane-api.md)
- [Client API](../control-plane/device-registry.md)
- [Errors and idempotency](api-reference.md)
- [Compatibility](../architecture/compatibility.md)
- [External Marketplace API contract](marketplace-api.md) — used by the Control import client

Breaking contracts require an explicit version/migration decision. Proposed
OpenAPI files are design artifacts, not evidence that a route is deployed.
