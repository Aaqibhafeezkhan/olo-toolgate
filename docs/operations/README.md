# Operate ToolGate

Use runbooks for the deployed version and preserve the data/custody needed to
reconstruct authorization history. Health and connectivity do not establish
permission to execute.

| Task | Runbook |
|---|---|
| Run and verify services | [Execution guide](execution-guide.md) |
| Assess target recovery and reconcile an uncertain effect | [Target-deduplicated recovery](target-deduplicated-recovery.md) |
| Diagnose a failure | [Debugging](debugging.md) and [device states](../control-plane/device-registry.md) |
| Tune container load and understand caching | [Container performance](performance.md) |
| Prepare external services and TLS | [Production deployment](../deployment/production.md) |
| Monitor authority, decisions and device adoption | [Enterprise operations](../enterprise-access-control/operations.md) |
| Back up and recover current-format state | [Reviewed recovery](../enterprise-access-control/operations.md) and [Quickstart persistence](../deployment/quickstart.md) |
| Deploy matching current Control and Gateway | [Fresh Control initialization](../control-plane/upgrades.md), [Gateway upgrades](../gateway/upgrades.md) |
| Deploy or roll back client packages | [Package lifecycle](../client/package-deployment.md) |
| Manage key custody | [Keys and credentials](../security/key-management.md) |

## Incidents

Identify the affected tenant, device, tool and invocation using redacted audit and
service logs. Disable or revoke affected authority through the current console/API
and verify denial at the effect boundary. Preserve evidence; unknown external
outcomes must not be retried blindly. Investigate Control/database/identity
availability before interpreting a Gateway failure as an executor problem.

Use the reviewed recovery procedure for compromised custody or damaged state.
Never copy an old database over live authority or reset identity to reconnect a
client. Check the [kill-switch specification](../control-plane/kill-switch.md)
and executor guides before relying on emergency push or break-glass delivery.
For a vulnerability, follow [private disclosure](../../SECURITY.md).
