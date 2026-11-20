# Component ownership and repository layout

| Component | Source | Owns | Boundary |
|---|---|---|---|
| Gateway | `apps/gateway/`, Rust | Caller authentication, MCP routing and observations | No database or independent authorization/permit signer |
| Control Plane | `apps/control-plane/`, Java | Tenant configuration, current authorization, review, permits and durable state | Does not execute arbitrary tool code |
| Console | `apps/admin-ui/`, React/TypeScript | Human authoring, administration and evidence presentation | Backend owns validation and security decisions |
| LangGraph runtime | `apps/langgraph-runtime/`, Python | Governed external graph/agent execution | No independent grant or effect authority |
| Endpoint Client | `apps/endpoint-client/`, Rust | Device identity, managed packages and brokered capabilities | Execution confinement depends on the declared runtime |
| Tool Host | `apps/tool-host/`, Rust | Server admission, verified content, broker and execution | Native and qualified OCI tiers have different boundaries |
| Quickstart | `apps/quickstart/` | Local composition, console and installation custody | Single-node evaluation; no HA guarantee |

| Directory | Contents |
|---|---|
| `crates/` | Shared Rust libraries |
| `backend-libs/` | Shared Java libraries |
| `packages/contracts/` | Canonical schemas/OpenAPI and generated language bindings |
| `tool-sdk/` | CLI, SDKs, canonical specifications and conformance fixtures |
| `bot/` | Editable bot sources and bundle build support |
| `examples/` | Tool and agent examples |
| `deploy/` | Launchers, Compose, Helm and deployment assets |
| `debug/` | Source-built persistent development installation |
| `config/initial/` | Initial group/configuration presets |
| `tools/`, `tests/` | Build/release/verification tooling and fixtures |
| `docs/` | Maintained user, developer, operator and security guides |
| `docs/plans/` | Genuinely unfinished work only |

The external Marketplace is outside runtime authorization; see
[registry integration](../control-plane/marketplace-integration.md).
Keep credentials and generated output ignored. Cross-component changes start in
[canonical contracts](../development/schema-workflow.md).
