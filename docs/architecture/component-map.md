# Component map

| Component | Stack | Owns | Boundary |
|---|---|---|---|
| Gateway | Rust | Caller authentication, MCP routing, current-authority requests and runtime observations | No database, local policy authority, effect signer or arbitrary tool execution |
| Control Plane | Java | Tenant graph, authorization, review, permit signing, device registry, deployment and durable state | Arbitrary tool code executes elsewhere |
| Admin UI | React/TypeScript | Human administration and impact/review presentation | Backend owns validation and security decisions |
| Endpoint Client | Rust | Device identity, signed package tools in a sandbox, the brokered files area | Current Control authorization and runtime confinement remain required |
| Tool Host | Rust | Server Java tool admission, verified content, broker and execution | Container/network isolation and result outbox remain unfinished ([#230](https://github.com/olo-labs/olo-toolgate/issues/230)) |
| Quickstart | Combined services | Single-node evaluation with local state/custody | No HA guarantee |

Control already supports importing from a configured external OLO Orgs registry;
see [Marketplace integration](../control-plane/marketplace-integration.md).
The Marketplace itself lives in a separate private repository and is hosted at
[olo-labs.org](https://olo-labs.org).

Read [trust boundaries](trust-boundaries.md) and
[device controls](../control-plane/device-registry.md). Create an
[ADR](../adr/README.md) before moving responsibility between components.
