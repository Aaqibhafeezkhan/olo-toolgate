# Operator Insights: Stage 7 integration test report

Step 7 (integration testing) ran on the complete `feature/olo-insights` branch: steps 1–6 plus `main` at `5429dfbd`.
It runs every release gate that can be run without external accounts or production-like hardware. The
performance gates were deferred by decision of the feature owner to
[#214](https://github.com/olo-labs/olo-toolgate/issues/214).

Design: [ADR 031](../adr/031-execution-correlation-operator-insights.md). Operator guide: [insights.md](insights.md).

## Result

| Gate | Result | Evidence |
| --- | --- | --- |
| Correlation | Pass | Every attempt gets its own execution ID, derived from its fencing token: `InsightsTest` re-queue, Tool Host `dispatch` tests and the endpoint client trace tests. |
| Compatibility | Pass after fix | Defect S7-1 (below): released endpoint clients rejected relayed tasks. Older SDK packages ignore `executionContext` (conformance `stdin/v2-execution-context`, Python SDK end-to-end). |
| Retry accounting | Pass | Re-queues, retries and duplicate state writes are counted once: `InsightsTest`, plus the concurrency and quota gates. |
| Projection recovery | Pass | A projector that fails mid-batch commits nothing, and the rerun counts every call once. |
| History fidelity | Pass | QUEUED → RUNNING → OUTCOME_UNKNOWN → SUCCEEDED and T11 re-queues before the projector runs (`InsightsTest`). |
| Ordering | Pass | Six concurrent ledger writers with two concurrent projectors: exact counts, no gaps, no deadlocks. A rebuild finds no drift. |
| Atomic capture | Pass | An injected fact-insert failure aborts the ledger transition; the retried transition keeps a contiguous fact sequence. |
| Late corrections | Pass | A T13 change after minute retention corrects hour and day cells exactly and does not recreate the expired minute. |
| Write amplification | **Deferred** (#214) | A preliminary SQLite measurement found about 1.75 Insights rows per fact. |
| Projector ownership | Pass | Two projector "replicas" running concurrently against one tenant never apply a fact twice. Ownership is the tenant transaction lock (ADR 031 §10). |
| Checkpoint lifecycle | Pass, with a limit | Retention removes facts and checkpoints together, and a rebuild after retention finds no drift. `OUTCOME_UNKNOWN` keeps its checkpoint for T13. Retention and projection are serialized by the same tenant lock, so they cannot run concurrently. A concurrent run was therefore not tested separately. |
| Span memory | Pass | 3,200 concurrent long-running calls against a 1,000-span budget, from 8 threads: the buffer stays within budget, and early decisions and drops are counted. |
| Reconciliation | Pass | Replaying every retained fact into an empty projection reproduces summaries, attempts, timing and cells exactly. |
| Quota | Pass | Over quota, re-queues, a T13 reconciliation and new calls keep aggregates exact. Status reports the tenant as suspended, and rebuild is refused (409). |
| Admission counters | Pass | Duplicate batches are counted once, and skipped sequences become source gaps with drop counts (`InsightsTest`, Gateway counter tests). |
| Database | Pass | Every gate above runs on SQLite and on PostgreSQL through the runtime role with forced RLS, each on a freshly migrated database. The full Control suites pass on both. |
| Performance | **Deferred** (#214) | A preliminary SQLite run suggests the commit-latency and span-CPU budgets are missed; see #214. |
| `NONE` | Pass | Ledger transitions, declared context, admission batches and span uploads write no row in any Insights table, and the exporter claims nothing. The Insights worker does not start in `NONE`. |
| Mode equivalence | Pass | `BASIC` and `DETAILED` produce identical cells and summaries for the same workload. |
| Tenant isolation | Pass | The operator listener accepts only operator-CA certificates. The tenant console does not serve the page. Sessions and parents stay within a tenant (`InsightsOperatorListenerTest`, sessions gate). |
| PII leakage | Pass, with a limit | A canary in ledger free text and in `tracestate` reaches no Insights table, operator response or OTLP export. Logs were not inspected automatically. |
| Span integrity | Pass | Senders can report only their own invocations and span names, and `source` is set from the authenticated sender (`InsightsSpansTest`). |
| Trace coverage | Partial | Host and client execution spans join the trace the tool received, and route-call spans nest under them (Tool Host `dispatch`, client trace tests). A connected-trace test across retry, reconnect and timeout paths through a running Gateway was not built. |
| Failure injection | Pass, with a limit | An unreachable Collector fails fast and backs off, and drops are counted. A 1,536-span flood keeps 512 and refuses the rest. The ledger and projector keep working throughout. Disk-pressure injection was not run. |
| UI correctness | Pass | Vitest covers every page state, the waterfall and the session timeline. Each step's UI was checked rendered in a browser. |

Not run, because accounts are not available:

- **Vendor smoke tests.** No Datadog or New Relic account was available, so delivery to either vendor is **unverified**.
- **What was verified.** Control's exported OTLP JSON was accepted and parsed by `otelcol-contrib 0.120.0`, and all three Collector configs pass its validator (step 5).
- **Still needed.** Delivery should be checked in a real account before relying on the vendor dashboards.

## Defects found and fixed

**S7-1. Released endpoint clients would fail every relayed call once Control is upgraded.** This was the Compatibility gate, and it was the most serious finding.

- **Cause.**
  - This feature added `traceparent`, `executionContext` and `collectSpans` to `RemoteToolTask`.
  - Endpoint clients decode tasks with `deny_unknown_fields`.
  - Clients run on users' devices and update separately from Control. Control's advertised `minimumClientVersion` is not enforced for relayed calls.
  - So a released client would reject the check-in reply that carried a task. `traceparent` is sent whenever the caller supplies one, so this happened even with Insights `NONE`.
- **Fix.**
  - Clients now name what they accept in a `Toolgate-Client-Features: remote-task-context.v1` header, on HTTPS check-ins and on the WebSocket upgrade.
  - Control sends the new task members only to clients that name the feature. Every other client receives exactly the released task shape.
  - Older Controls ignore the header, so newer clients keep working against them. See ADR 031 §13.
- **Tests.** `EndpointTest.relayedTaskContextGoesOnlyToClientsThatAcceptIt` and the client's `every_request_names_the_task_members_this_client_accepts`.

**S7-2. CI never completed on the feature branch.**

- **Cause.** `test_release_documents` had been failing on `main` since `13496a25`. That made `foundation / python` fail, and the job stops before its PHP and Helm gates. The CI runs for steps 3–5 were also cancelled when their PRs merged.
- **Fix.** The test was fixed in the step 6 PR. The first complete green CI run was on `a2e86282`.

## Not defects, recorded

- **Flaky test (pre-existing).** `service::tests::power_off_reports_once_and_power_on_resumes_without_reenrollment` in the endpoint client failed once in a loaded full-workspace run. It passed in four isolated runs and in two further full runs of the client crate. It does not touch Insights code.
- **No `context_delivered` flag.** The delivery plan asked for the attempt summary to record `context_delivered = false` when an old peer could not receive the context. This was not built. An old client now gets the task without context (S7-1), and Insights does not flag the loss. The client execution span is simply absent.

## Running the gates

```bash
python tools/control/check.py
```

The gates are `InsightsReleaseGatesTest`, `InsightsTest`, `InsightsSpansTest` and `InsightsOperatorListenerTest` in
`apps/control-plane`. The PostgreSQL variants need the database that `tools/control/check.py` provides; CI runs both.
`InsightsBenchmarkTest` runs only with `TOOLGATE_INSIGHTS_BENCHMARK=1` (see #214).
