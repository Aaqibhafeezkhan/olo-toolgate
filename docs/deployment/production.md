# Production deployment preparation

ToolGate is in Beta Release. See the [current limitations](../enterprise-access-control/README.md#current-limits) and linked enhancement tickets before qualification.

Deploy matching Control, Gateway, console, contract and client versions. Control
uses external PostgreSQL; Gateway requires live Control authority. Quickstart is
single-node evaluation software and does not establish high availability ([#241](https://github.com/olo-labs/olo-toolgate/issues/241)).

## External prerequisites

- Provision trusted identity verification and current workload/user bindings.
- Use separate migration and runtime database roles, with verified PostgreSQL TLS.
- Provision distinct effect, snapshot, device and release custody outside images,
  Git and Helm values. Supply existing protected Secret references where required.
- Configure explicit service origins, TLS trust and network/egress restrictions.
  Keep administrative and runtime ingress appropriately separated.
- Prepare reviewed configuration, complete grants and execution bindings. Installing
  a client or approving its connection does not grant runtime access.

## Deployment and qualification

Use the [Helm chart](../../deploy/helm/olo-toolgate/README.md) or
[Gateway/Control Compose](../../deploy/compose/GatewayControl/README.md), with
[Control settings](../control-plane/configuration.md) and
[Gateway settings](../gateway/configuration.md). Validate chart values against the
checked-in schema and render before applying.

Run [execution checks](../operations/execution-guide.md), then rehearse outage,
revocation, upgrade and [signed recovery](../enterprise-access-control/operations.md)
with the intended topology. Measure your workload; the repository's small-fixture
results are not a capacity commitment.

Review [current limitations](../enterprise-access-control/README.md), especially
unfinished Tool Host isolation and delivery ([#230](https://github.com/olo-labs/olo-toolgate/issues/230)). Marketplace, additional vault
adapters and multi-region operation must not be assumed available.
