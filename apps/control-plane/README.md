# Control Plane

Java 21 / Quarkus service owning tenant configuration, current authorization,
review, permit signing, device registry, deployments, durable invocation state and
audit. It serves the console and never executes arbitrary tool code in its process.

Production uses PostgreSQL with separate migration/runtime roles and externally
provisioned identity/custody. Quickstart supplies a separate composition with local
login and SQLite by default. The domain/application boundary uses store/codec ports;
HTTP, verified identity and persistence adapters provide the runtime integrations.

First initialization applies the complete V1 schema once (PostgreSQL Flyway or
Quickstart SQLite). Pre-release database histories are unsupported; initialize a
new empty database or data directory. See the [database initialization guide](../../docs/control-plane/upgrades.md).

Run `python tools/control/check.py` for module verification and
`python tools/control/container.py` for the production-image checks.

- [Configuration](../../docs/control-plane/configuration.md)
- [API](../../docs/api/control-plane-api.md)
- [Administration and approvals](../../docs/control-plane/README.md)
- [Upgrade and recovery](../../docs/control-plane/upgrades.md)
- [Managed package deployment](../../docs/client/package-deployment.md)
- [Designated-client tool builder](../../docs/client/tool-builder.md)
- [Server Tool Host](../tool-host/README.md)
