# Control Plane and console

Control owns tenant configuration, group membership/grants, current authorization,
approvals, purpose-specific permit signing, device registry, deployments, durable
invocations and audit. It serves the administration console. Arbitrary tool code
runs in an executor outside the Control process.

Scoped administrators submit reviewed changes. Super Admin has an explicit
[audited management exception](../adr/022-super-administrator-review-exception.md).
Management authority never substitutes for runtime grants. Applicable BLOCK wins;
ASK approvals remain bound to the exact operation and current authority.

## Administration and operation

- [Console](admin-ui.md) and [Administration menu](configuration-menu.md)
- [Configuration](configuration.md) and [import/export](import-export.md)
- [Device registry and approval](device-registry.md)
- [Group snapshots and publication](policy-bundles.md)
- [ASK approvals](approvals.md) and [access model](../security/enterprise-access-control.md)
- [Package deployment](../client/package-deployment.md) and [custom tool builder](../client/tool-builder.md)
- [API](../api/control-plane-api.md), [upgrades](upgrades.md) and [recovery](../enterprise-access-control/operations.md)
- [Invocation run events and the Test panel](run-events.md)
- [Operator Insights](insights.md)
- [Operator Insights: Stage 7 integration test report](insights-stage7.md)
- [Tenant data isolation foundation and remaining release gates](tenant-isolation.md)
- [Multitenant deployment, recovery and qualification](../deployment/multitenant.md)

## Design references

[Invocation state](invocation-state-v2.md), [kill switch](kill-switch.md) and
[migration specifications](migrations-v2/README.md) define contracts used by
conformance tests. A design does not establish that every executor implements it.
Check each component guide for its supported behavior and current limits.
