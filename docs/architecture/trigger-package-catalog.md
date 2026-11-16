<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Trigger package catalog and adoption requirements

Every source below must use the same package/profile adoption path described in
[ADR 066](../adr/066-generic-trigger-package-adoption.md). No new source gets a
watch-specific Compose service, operator branch or ambient credential. Profiles
own source settings, credential references, filters and workflow bindings.
Actual secrets remain in scoped private credential custody. Event content is
untrusted workflow input and never runtime authority.

The original implemented source is `examples.hotfolder-watch/new-file` (client POLL,
read-only managed files, state snapshots). The independent
[signal fixture](../../tests/fixtures/trigger-packages/signal-watch/README.md)
tests adoption of an external package with another configuration/event shape;
it is not a production observability connector.
[`examples.log-watch/error-spike`](../../examples/java-tools/log-watch/README.md)
now supplies bounded managed-file POLL observation, with filtering, spike windows,
cooldown, prefix/offset checkpoints and explicit rotation limitations. Its
[profile](../../deploy/compose/WorkflowStack/configurations/log-triage/README.md)
uses the existing generic package adoption path. Native PUSH, arbitrary host logs,
Docker logs, Windows Event Logs and large-file streaming remain unimplemented.
The remaining integrations below are requested work, **not implemented connectors**.

| Priority | Package/export family | Required behavior and acceptance | Generic dependency / present gap |
| --- | --- | --- | --- |
| 1 | `webhook-receiver` | Bounded signed HTTP/JSON POST; configured provider verification; freshness/replay rejection, stable delivery identity, tenant isolation and immediate configured-workflow admission. Cover valid request, altered body/signature, duplicates, changed duplicate body, expiry, revocation and overload. Prometheus/Grafana/PagerDuty/Datadog/Actions need individually verified provider adapters; a canonical HMAC endpoint alone is not proof of all provider integrations. | Lease-bound server placement and a generic inbound HTTP broker with declarative authentication/custody. #274 M7/M8; these are not present merely because a package declares a server trigger. |
| 1 | `log-watch` — managed-file POLL slice implemented | Incremental local log offsets, rotation/truncation/restart handling, bounded pattern matching and spike windows; suppress noise before model admission. Qualify managed files, host logs, container logs and Windows Event Logs separately. Never treat a container's own log/metrics namespace as the host's. | The bounded managed-file package is implemented. Qualified host log/event capabilities, PUSH/reconcile, native version keys and large-file streaming remain M7/M8 work. Current managed-files reads do not grant `/var/log`, Docker logs or Windows Event Logs. |
| 1 | `health-probe` / `metric-threshold` | Profile-declared CPU/RAM/disk/port/service probes, consecutive-failure thresholds, hysteresis and recovery events; no continuous noisy LLM calls. Include host identity, real host telemetry and least-privilege negatives. | Qualified generic telemetry/service/TCP capabilities and source supervision, M7/M8. Reading process/container metrics does not qualify host health. |
| 2 | `email-watch` | IMAP/Graph/Gmail adapters; stable message/thread identities, bounded MIME/attachments, deduplication, restart and credential rotation. Separate mailbox reads from outgoing replies or attachment/tool effects. | Server broker routes/TCP/secrets, M8; IMAP PUSH also depends on tunnels and M18. |
| 2 | `chat-mention` / `slash-command` | Slack/Teams/Mattermost adapters; signature verification, tenant/team/channel/user bindings and stable event identities. An approval card refers to the governed approval record; chat text cannot approve an effect by itself. | Generic authenticated ingress/provider routes, M8; governed actions/approvals remain separate. |
| 2 | `ticket-watch` | Jira/ServiceNow/Zendesk/GitHub adapters; created/labeled/updated filters, stable revision IDs, bounded context and scoped credentials. Posting a suggested fix is an independently authorized tool effect. | Generic ingress or HTTP polling/secret broker, M8. |
| 3 | `queue-consume` | Kafka/RabbitMQ/NATS/Redis Streams adapters; consumer groups, partition ownership, bounded credits/backpressure and durable checkpoint/ACK ordering. A source retry must not silently retry an uncertain workflow effect. Qualify broker loss, failover, replay and DLQ re-drive separately. | Server placement/leases/TCP or route broker, M8; event routing/DLQ M10 does not itself implement queue-client adapters or exactly-once effects. |
| 3 | `db-cdc-watch` | PostgreSQL LISTEN/NOTIFY and Debezium adapters; configured tables/channels, stable transaction/change IDs, privilege checks and durable positions. LISTEN/NOTIFY alone is not a durable change log; disconnect-loss behavior must be explicit. | Server broker/secrets and qualified replay/checkpoint behavior, M8. |
| 4 | `cron-schedule` | Profile timezone/cron, DST, overlap/misfire/catch-up policy, deterministic scheduled occurrence identity and guarded configured-workflow admission. | Dedicated generic Control scheduling source; M13 (Quartz), not a disguised fixed-interval client poll. |
| 4 | `git-event` | GitHub/GitLab signature/replay verification, explicit repository/event/ref filters and stable delivery IDs. GitOps config sync validates and reviews a pinned profile revision; webhook contents cannot publish or grant permissions directly. | Generic ingress/provider adapters, M8, plus the normal reviewed profile pipeline. |

For each package, its README must explain installation, supported executor and
capabilities, profile/credential fields, how to produce a real event, expected
workflow/agent behavior, where to inspect outputs, and failure/replay limits.
Provider-specific verification uses actual primary documentation and executed
positive/negative tests; a sample descriptor or structural profile check earns
no runnable-source claim.

The user-selected test strategy is deterministic provider mocks, registered
per package in the [parallel foundation test stage](../../tools/triggers/README.md).
Each new package owns its mock protocol fixtures and positive/negative cases.
No real provider credentials are required for that stage. Current registrations
cover the two implemented managed-file packages and the independent adoption
fixture; missing provider packages are not represented by placeholder passing
matrix entries. Stack/runtime integration remains a separate acceptance layer.

The catalog extends the requested product backlog. It adds no earned points to
the preserved #274 100-point module baseline; no new package weights are assigned
or silently absorbed into completed modules. The authoritative
[module status](triggers-implementation-status.md) records delivered evidence and
any future explicit baseline amendment. Priority is infrastructure first, then
communication/ticketing, streaming/CDC, and scheduling/GitOps.
