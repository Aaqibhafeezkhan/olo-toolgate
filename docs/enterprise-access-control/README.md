# Evaluate ToolGate for enterprise use

ToolGate provides group-based authorization and reviewed administration for AI
tools. Evaluate the current development release against your own identity,
execution, availability and recovery requirements before adopting it.

## Supported boundaries

- Users inherit rights through Teams; Agents through Agent Groups. Tool Groups
  have explicit execution bindings to Device Groups. Default membership grants no access.
- Control checks current authority before protected effects, secret delivery and
  result access. Gateway does not authorize from an offline snapshot.
- Device enrollment binds a reviewed identity/key. Connection approval,
  enablement, runtime grants and resource checks are separate prerequisites.
- Scoped administrators use independent review. Super Admin has an explicit,
  audited [management and self-review exception](../adr/022-super-administrator-review-exception.md);
  it does not bypass runtime grants or policy obligations.
- Permits are short-lived and consumed once. Unknown external outcomes are fenced;
  this is not an exactly-once guarantee for downstream effects.

## Evaluation sequence

1. Run a [local Quickstart](../getting-started/one-minute-quickstart.md) and model a
   real Team, Agent Group, Tool Group and Device Group.
2. Exercise allowed, denied, approval-required and revoked calls against a real
   enrolled executor. Verify that disabling any required authority stops effects.
3. Review the [security model](../security/enterprise-access-control.md),
   [trust boundaries](../architecture/trust-boundaries.md) and
   [verification procedures](../testing/README.md).
4. Prepare [production deployment](../deployment/production.md), external identity
   and key custody, and separate migration/runtime database roles.
5. Rehearse [migration and signed recovery](operations.md), outages and upgrades.
   Measure capacity with your workload and topology.

## Current limits

Quickstart is single-node evaluation software. Tool Host process launch and broker
checks exist, but container/network isolation and durable result retry remain
unfinished. Managed endpoint OCI confinement is a different execution path.
The Marketplace is hosted separately at [aol-labs.org](https://aol-labs.org). Java and Python authoring implementations
are available; TypeScript and .NET authoring APIs remain specifications.

Nested/dynamic groups and offline protected effects are unsupported. Recorded
acceptance and small-fixture performance results apply to the tested revision and
environment. They do not establish your organization's deployment readiness,
capacity or compliance certification.

[Standards alignment](standards-alignment.md) explains design relationships.
Use [SUPPORT.md](../../SUPPORT.md) for project support and
[SECURITY.md](../../SECURITY.md) for private vulnerability reporting.
