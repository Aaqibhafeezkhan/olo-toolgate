# Architecture overview

Control is the authority for current tenant configuration, authorization,
approvals, single-use permits and durable invocation state. Gateway validates
caller identity, obtains Control's decision and routes the call to the selected
executor. The executor checks the permit and current authority before effects.

```text
AI / MCP client -> Gateway <-> Control <-> PostgreSQL
                    |            ^
                    v            |
             Registered executor-+
             (Endpoint Client or Tool Host)
                    |
                    v
              Approved tool/resource
```

## Identity and permission

Users belong to Teams; Agents to Agent Groups; each Tool has one Tool Group;
Devices belong to Device Groups. Complete grants retain these relationships,
with explicit Tool Group/Device Group execution bindings. Overlapping memberships
cannot combine unrelated rights. Default memberships grant nothing.

Device identity comes from authenticated service context. Enablement and
connection approval are independent of runtime permission. An ALLOW decision
requires every applicable gate; ASK adds approval obligations; BLOCK wins.
See [device controls](../control-plane/device-registry.md) and
[enterprise authorization](../security/enterprise-access-control.md).

## Deployments

Quickstart runs the services together with SQLite by default and persistent
local custody. It is single-node evaluation software ([#241](https://github.com/olo-labs/olo-toolgate/issues/241)). Separate Gateway and
Control deployments use external PostgreSQL and separately provisioned identity,
keys and runtime configuration. The console is served by Control.

Managed endpoint packages use signed fleet assignments and OCI confinement.
The server Tool Host supports Java execution, content verification and brokered
routes; container/network isolation and a durable result outbox are not implemented ([#230](https://github.com/olo-labs/olo-toolgate/issues/230)). These execution paths have different current limits.

The Marketplace is developed in a separate private repository and hosted at
[olo-labs.org](https://olo-labs.org).
A future community signature will establish release provenance, never organization
deployment authority or runtime permission.

## Further reading

- [Component ownership](component-map.md)
- [Trust boundaries](trust-boundaries.md) and [data flows](data-flows.md)
- [Contracts](shared-contracts.md) and [compatibility](compatibility.md)
- [Repository layout](monorepo-layout.md)
- [Enterprise evaluation](../enterprise-access-control/README.md)
