# Architecture

ToolGate separates current authorization, administration and execution. Control
owns tenant state and policy decisions; Gateway authenticates and routes requests;
registered executors perform protected effects under Control-issued permits.

```text
AI / MCP client -> Gateway -> Control: current authorization and permit
                    |
                    +-> Endpoint Client -> managed local tool
                    +-> Tool Host       -> server Java tool

Administration console -> Control -> PostgreSQL (SQLite in Quickstart)
```

Every protected effect checks current authority. Registration, deployment,
connection approval and cached metadata do not grant runtime access. Unavailable
Control fails closed. Tools execute outside the Control process.

Quickstart combines services for single-node evaluation. Production separates
services and external state/custody. Marketplace applications are scaffolds and
do not participate in current runtime authorization.

Read the [architecture overview](docs/architecture/overview.md),
[component map](docs/architecture/component-map.md),
[trust boundaries](docs/architecture/trust-boundaries.md),
[shared contracts](docs/architecture/shared-contracts.md) and
[architecture decisions](docs/adr/README.md). Discuss proposed capabilities in GitHub issues.
