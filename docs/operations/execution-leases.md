<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Tool Host execution leases and evidence recovery

Tool Hosts consume a single-use admission permit, then obtain renewable execution
authority from Control before starting a worker. Control persists the admitting
device, fence and initial lease in the admission transaction. The lease lasts at
most 30 seconds and never extends past the invocation deadline.

`POST /api/control/v1/access/invocations/{id}/heartbeat` requires the admitting
device's direct certificate and the original fencing token. Every renewal checks
current policy, epoch, approval and credential validity. An expired or cancelled
lease cannot be renewed. Progress and input-request fields are refused until the
task-input implementation ships.

The healthy supervisor renews at most every 10 seconds. Explicit refusal ends
execution immediately; unavailable Control cannot extend the previous lease. A
monotonic expiry timer bounds a hung renewal. The existing process deadline is
also enforced. These bounds concern the supervised process on a healthy host;
process descendants and compromised hosts require the containment milestone.

## Persistent host state

Keep the Tool Host's configured `workDir` on a persistent, private filesystem.
Do not share it between independently running supervisors. The host writes:

- `admission-spool/`: a durable uncertainty record after Control consumes the
  permit and before the host acknowledges admission or starts a worker;
- `result-outbox/`: the exact result report, persisted before transmission.

Files are flushed before atomic publication; their parent directories are flushed
on Unix. Unix directories use mode 0700 and reports mode 0600. Protect this data
as execution evidence: results can contain business data. A result is removed only
after Control acknowledges it. Delivery retries every five seconds, independently
of the running worker and after restart. A restart converts surviving admission
records into `OUTCOME_UNKNOWN` evidence; it never restarts their tools. If a crash
leaves both a completed result and its admission marker, both observations can
arrive; an uncertainty observation does not override the completed outcome.

Control retains original result reports in append-only
`control_execution_evidence`, keyed by tenant, invocation, fence and report digest.
Late reports from the admitted executor remain evidence after invocation expiry
or cancellation. They do not restore a lease or silently overwrite a terminal or
reviewed outcome. Reconcile uncertain effects through the existing reviewed
reconciliation workflow. Inactive device certificates still fail authentication.

Monitor disk capacity and `result evidence could not be persisted`,
`result outbox delivery incomplete` and `admission intent cleanup incomplete`
diagnostics. Never delete a pending spool or outbox to make a tool retry. Missing
host files are not proof that a downstream effect did not occur.

## Compatibility and scope

Control database migration V2 adds leases and immutable execution evidence for
both PostgreSQL and SQLite. Upgrade Control before Tool Hosts: new hosts require
the heartbeat endpoint and fail closed if it is unavailable. Older Tool Hosts do
not provide healthy-host renewal supervision; do not claim the bound for them.

The [preserved issue #230 plan](../plans/zero-trust-tool-execution.md) describes
the complete roadmap. This lease/evidence increment does not implement the full
roadmap: business-key operation binding, the per-effect ledger and exact effect
consent, cooperating-target fencing, operation
finalization across attempts, externally anchored audit verification,
sender-constrained run credentials, provenance and broker OAuth remain separate
work. Third-party target outcomes are not proven by dispatch or permit consumption.

Python workers can additionally use the [qualified rootless OCI tier](rootless-tool-host.md)
to contain filesystem, network and process authority. Its cgroup teardown covers
the worker container; native hosts retain the direct-process limitation above.
