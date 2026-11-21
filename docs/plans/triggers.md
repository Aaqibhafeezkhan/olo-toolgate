# Remaining trigger work

Status: unfinished work for [issue #274](https://github.com/olo-labs/olo-toolgate/issues/274).
The [trigger architecture](../architecture/triggers.md) and
[management guide](../control-plane/trigger-assignments.md) describe current behavior.

## Execution capabilities

Implement and qualify generic PUSH/file watching (M7), server triggers on Tool Hosts
(M8) and Python SDK parity (M9). Reuse signed descriptors, leases, current authority and
generic broker capabilities. A source-specific sidecar, name switch or broad
host credential is not an acceptable substitute. Qualify each supported executor
and source boundary independently.

## Routing and actions

Complete end-to-end acceptance for client ingestion, lifecycle/rollout, event
routing/retention, coarse information-flow enforcement and socket subscriptions.
Require tenant/owner negatives, forged/revoked reports, durable cursor/ACK ordering,
bounded retention, overload/backpressure and reconnect cases.

Qualify third-party observation of permitted trigger events and in-progress status
under the [external application integration requirements](workflow-application-integration.md).
Require an existing authenticated OLO user and user-bound token, current role and
resource checks, filtered subscriptions and revocation during delivery. Trigger
observation must not grant workflow execution or access to another user's results.

Implement remaining schedules, authorized actions, composite MCP and field-level
information-flow enforcement. Scheduling must define timezone/DST, misfire,
overlap and catch-up semantics. Event content cannot select a more privileged
actor, workflow or effect. An action must use current governed MCP authorization.

## Provider sources

Provider packages need deterministic positive/negative mock protocols and
separate integration qualification:

| Family | Required boundary |
|---|---|
| Webhooks and Git events | Body/signature/freshness/replay verification, exact tenant/repository routing |
| Host/container logs and health probes | Approved host identity, least-privilege telemetry and bounded streaming |
| Email and IMAP PUSH | Scoped mailbox custody, MIME/attachment bounds, durable identity and qualified TCP/tunnels |
| Chat and tickets | Provider identity/signature checks, scoped team/channel/project mappings |
| Queues and CDC | Partition ownership, replay/ACK/checkpoint ordering and explicit disconnect-loss semantics |
| Cron scheduling | Deterministic occurrence identity and guarded workflow admission |

Managed-file POLL examples do not qualify arbitrary host logs or any provider
above. Delivering an event once does not prove exactly-once tool effects.

## Release acceptance

Run module-level tests on SQLite/PostgreSQL where applicable, contract regeneration,
cross-tenant negatives and the complete end-to-end scenarios owned by the change.
Qualify resource safety, shutdown, revocation, crash/reconnect and replay before
claiming release readiness. Resource bounds are functional requirements; no
throughput/latency/SLO claim follows without separately executed measurements.

Use current contracts and fresh schemas only. Fold schema changes into fresh
initialization; reject unsupported existing stores without conversion or deletion.
