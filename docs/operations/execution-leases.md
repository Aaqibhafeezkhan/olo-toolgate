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

## Current deployment scope

Use matching current Control/Tool Host artifacts and fresh PostgreSQL/SQLite
schemas. A host requires the heartbeat/lease contract and fails closed when
current authority is unavailable. Unsupported stores are rejected intact.

Leases bound declared supervision behavior; they do not prove third-party
target completion or independent target-side revocation. Native hosts retain
their direct-process trust boundary. Python workers can use the
[qualified rootless OCI tier](rootless-tool-host.md); apply its exact host,
image, resource and teardown prerequisites.
