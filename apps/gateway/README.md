# Gateway

Rust service for authenticated MCP ingress and routing. Control supplies current
authorization and signs permits; Gateway has no local policy authority, effect
signer or database access.

Run with administrator-provisioned `TOOLGATE_GATEWAY_CONFIG` and
`TOOLGATE_GATEWAY_CREDENTIALS` as described in
[configuration](../../docs/gateway/configuration.md). The
[online example](../../docs/examples/gateway-online.json) is a configuration
template, not a working credential or standalone authorization setup.

```sh
cargo run -p olo-toolgate-gateway --locked
cargo test -p olo-toolgate-gateway --locked
```

Use `python tools/gateway/container.py` for the isolated production-image gate.
See [deployment](../../docs/gateway/deployment.md),
[MCP API](../../docs/api/gateway-api.md),
[client relay](../../docs/client/server-mcp.md) and
[Tool Host](../tool-host/README.md) for the connected execution paths.
