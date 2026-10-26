# Operator Insights

Operator Insights shows installation operators how tools are used and how they behave across every tenant: call
volume, outcomes, latency, retries, admission denials, and the history of any one invocation. It is not shown to
tenant administrators or callers. Design: [ADR 031](../adr/031-execution-correlation-operator-insights.md).

Insights records metadata only. Tool arguments, outputs, secrets and fencing tokens are never stored.

## Modes

One setting, the same for Control and every Gateway:

| `TOOLGATE_INSIGHTS_MODE` | Effect |
| --- | --- |
| `NONE` (default) | Nothing is written. No Insights table is touched and the projector does not run. |
| `BASIC` | Each state-changing ledger transition writes one fact row in the same transaction. Control projects facts into summaries and aggregates; Gateways send admission counters. |
| `DETAILED` | As `BASIC`. Span collection is added in a later step. |

Invalid values stop startup. Platform tracing (`toolgate.control.trace-export-enabled`) is unaffected by the mode.

**Cost on the execution path.** In `BASIC`/`DETAILED` every state-changing transition does one extra `UPDATE` (the
per-invocation fact sequence) and one `INSERT` (the fact) inside its transaction. Like the audit row, a failure of
that write fails the transition, which retries normally. Everything else (projection, retention, queries, counter
delivery) runs in the background and can never fail or delay a call.

## Control settings

| Property (environment) | Default | Meaning |
| --- | --- | --- |
| `toolgate.control.insights.mode` (`TOOLGATE_INSIGHTS_MODE`) | `NONE` | Collection mode |
| `toolgate.control.insights.fact-retention-days` (`TOOLGATE_INSIGHTS_FACT_RETENTION_DAYS`) | 7 | How long transition facts of finished invocations are kept (0–30, at most summary retention) |
| `toolgate.control.insights.summary-retention-days` (`TOOLGATE_INSIGHTS_SUMMARY_RETENTION_DAYS`) | 30 | Invocation and attempt summaries (1–90) |
| `toolgate.control.insights.max-summary-rows` (`TOOLGATE_INSIGHTS_MAX_SUMMARY_ROWS`) | 2,000,000 | Per-tenant summary quota |
| `toolgate.control.insights.minute-retention-hours` | 48 | Minute aggregates |
| `toolgate.control.insights.hour-retention-days` | 90 | Hour aggregates and admission counters |
| `toolgate.control.insights.day-retention-days` | 396 | Day aggregates |
| `toolgate.control.insights.interval-ms` | 2000 | Projector cadence |

Insights rows do not count toward a tenant's storage capacity, so they can never make a tenant's own writes fail.
Retention and the quota bound them instead.

## Gateway settings

Set `TOOLGATE_INSIGHTS_MODE` on each Gateway, or `"insights": {"mode": "BASIC", "gatewayId": "gw-eu-1"}` in its
configuration file. `gatewayId` names the Gateway in gap reports; it defaults to the host name.

The Gateway counts requests it answers without starting an execution: `UNAUTHENTICATED` (401), `POLICY_DENIED`
(403), `RATE_LIMITED` (429), `INVALID_REQUEST` (400) and `APPROVAL_REQUIRED`. It sends closed minutes to Control every
10 seconds as numbered batches, and a final batch on graceful shutdown. Counters are **best-effort**:

- memory is bounded (4,096 counters, 64 undelivered batches); anything dropped is reported as `droppedRequests`;
- a skipped sequence, or a Gateway process that stops without its final batch, appears in the status as a source gap
  (at most ten seconds of that Gateway's denials can be missing).

## The operator API

Control serves the Insights API on its own HTTPS listener, off unless configured. It is separate from tenant
ingress (8082) and the probe port (9092), and accepts only clients presenting a certificate from the operator CA. No
tenant credential is accepted.

| Property (environment) | Meaning |
| --- | --- |
| `toolgate.control.insights.operator.port` (`TOOLGATE_INSIGHTS_OPERATOR_PORT`) | Listener port; unset disables the listener |
| `toolgate.control.insights.operator.host` (`TOOLGATE_INSIGHTS_OPERATOR_HOST`) | Bind address, default `127.0.0.1` |
| `toolgate.control.insights.operator.certificate-path` | Server certificate (PEM) |
| `toolgate.control.insights.operator.private-key-path` | Server private key (PEM) |
| `toolgate.control.insights.operator.client-ca-path` | Operator CA (PEM); only certificates it signed are accepted |

Keep the operator CA private key offline and issue one short-lived client certificate per operator. Every request is
logged to the `toolgate.audit.insights` category with the certificate subject, method, path, tenant filter and status;
search values are never logged. Self-hosted installations configure the listener the same way for their own operators.

| Request | Returns |
| --- | --- |
| `GET /insights/v1/status` | `InsightsStatus`: mode, projector backlog, tenants over quota, source gaps |
| `GET /insights/v1/series?granularity=MINUTE\|HOUR\|DAY&from=&to=[&groupBy=TOOL\|CLIENT][&tenant=][&tool=\|&client=]` | `InsightsSeries`: per-bucket calls, durations and histograms by outcome, plus admission counts |
| `GET /insights/v1/invocations?from=&to=&tenant=[&tool=][&client=][&outcome=][&after=][&limit=]` | `InsightsInvocationPage`, newest first |
| `GET /insights/v1/invocations?from=&to=&search=ID` | Search across tenants by invocation, execution, tool-call, session or agent-run ID |
| `GET /insights/v1/invocations/{tenant}/{id}` | `InsightsInvocationDetail`: summary, attempts and, while facts are kept, transitions |
| `POST /insights/v1/rebuild` (`InsightsRebuildRequest`) | Recomputes a tenant's aggregates for up to 31 days from its summaries; `dryRun` only compares |

Schemas: [insights.schema.json](../../packages/contracts/schemas/v2/insights.schema.json). Series cover at most 2,000
buckets. Percentiles come from merged histograms, never from averaged percentiles; compute p95 only with 20 or more
samples and p99 with 100 or more.

## The Insights page

Open `https://<operator-host>:<port>/` in a browser that holds an operator client certificate; the listener redirects
to the page. The page and its assets are served only by the operator listener. The tenant console never serves it,
and its data requests go to the same origin, so the certificate authenticates them too.

- **Status** comes first. With `NONE` the page explains how to enable Insights and requests nothing else. If the
  status cannot be read it offers a retry, rather than showing an empty dataset.
- **Overview:** logical calls, success rate (succeeded ÷ (succeeded + failed), with the counts), failures with
  partial and unknown outcomes, p50/p95/p99 with the number of measured calls, and admission denials. The page warns
  when admission counters have gaps.
- **Calls by outcome** and **Execution duration**: hourly or per-minute UTC buckets chosen from the range, hover
  details, and a table view for each chart. Buckets past retention are marked expired, never drawn as zero, and a
  bucket with too few samples has no percentile point.
- **Busiest tools:** select one to filter the page by it.
- **Invocations:** browse a tenant's calls by outcome, or find a call in any tenant by its invocation, execution,
  tool-call, session or agent-run ID. The detail window shows identity and correlation (with copy buttons; declared
  IDs are marked as unverified), timing, every attempt with its execution ID, and the ledger transitions while they
  are retained.


- **Facts.** One immutable row per state change, numbered per invocation on the ledger row. Lease renewals write none.
- **Projector.** Every few seconds Control projects each tenant in that tenant's own write transaction, so two Control
  replicas never project one tenant at once. Facts are applied strictly in order; a missing number holds that
  invocation back and is counted. A checkpoint stores what each invocation contributes to the aggregates, so a later
  change (a retry, a reconciled `OUTCOME_UNKNOWN`) subtracts it exactly; nothing is counted twice.
- **Outcomes.** `PENDING_APPROVAL`, `IN_PROGRESS`, `SUCCEEDED`, `FAILED`, `PARTIAL`, `CANCELLED`, `EXPIRED` and
  `OUTCOME_UNKNOWN`, from the ledger state. Failures whose deadline had passed are `TIMEOUT`; other failures are
  `TOOL_ERROR`.
- **Attempts.** Each transition into `EXECUTING` starts an attempt, with the execution ID derived from that attempt's
  fencing token. A re-queue ends the attempt as failed.
- **Timing.** All times are Control commit times. Queue time runs from the first `QUEUED` to the first `EXECUTING`;
  execution time is the last attempt's; end-to-end runs from acceptance to the first terminal state.
- **Aggregates.** Calls and duration histograms per tool and per client (binding), at minute, hour and day. Buckets
  use the invocation's acceptance time, so a late outcome corrects the bucket it started in. A correction to a bucket
  past its retention is skipped.

## Retention, quota and rebuild

Every minute the projector also applies retention for each tenant. It deletes the facts and checkpoint of a finished
invocation together, after fact retention; summaries after summary retention; and aggregates per granularity. If a
tenant reaches the summary quota, new summaries of finished invocations stop until it falls below 90% of the quota.
Aggregates stay exact, because checkpoints are kept. Status reports the tenant as suspended.

`POST /insights/v1/rebuild` with `dryRun: true` compares the live aggregates with a recomputation from summaries and
reports how many cells differ. With `dryRun: false` it replaces the differing cells. It is refused for ranges older
than summary retention and for tenants currently over quota, because their summaries are incomplete.

## Limits of this release

- `DETAILED` collects nothing beyond `BASIC` until spans are added (plan step 5).
- The cause of a failure is inferred: a failure after the invocation's deadline is a timeout. `not_started`
  refusals are not yet distinguished from tool errors.
- `INPUT_REQUIRED` and `CANCEL_REQUESTED` do not change the ledger state, so they write no fact.
- Admission counters do not include kill-switch refusals separately.
