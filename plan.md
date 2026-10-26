# Tool execution correlation and operator insights

## Objective

Give every tool call a consistent execution context (tenant, client, user, session, agent run, logical call, attempt,
trace) and use it for operator-facing performance and debugging insights: a built-in Insights page and optional
OpenTelemetry export to Datadog, New Relic or another OTLP backend.

Insights is an **installation-operator tool**. It spans all tenants of an installation and is never shown to tenant
administrators or callers. In a self-hosted installation the customer is the operator and sees their own installation.

This is a plan. Nothing here is implemented yet. Normative decisions land as an ADR (step 1 in §8).

## Principles

- **Operator scope.** Cross-tenant data is visible only to the installation-operator role. No tenant-scoped API, admin
  view, response header, error or export destination exposes another tenant's telemetry or an aggregate including it.
- **The ledger is authoritative.** Insights is a derived, rebuildable projection of committed execution state. It never
  becomes a second source of truth for outcomes.
- **Mandatory state is untouched.** Ledger, run events, audit and invocation records keep their semantics and
  retention. Insights adds projections and optional spans; it never replaces or gates them.
- **One explicit coupling to execution, nothing else.** In `BASIC`/`DETAILED` exactly one Insights write is part of the
  ledger transaction: the transition fact (§4). It is deliberately coupled to the ledger, just as audit rows already
  are: if the database cannot take it, it cannot take the transition either. Its latency has a written budget and is
  measured, not assumed to be zero. Everything after that point (projection, span handling, export, the UI) is
  asynchronous and bounded, and its failure never fails or delays an invocation. Operators who cannot accept the
  coupling run `NONE`.
- **Metadata, not content.** Arguments, outputs, secrets, secret handles and conversation content are never captured.
  Attributes come from an allowlist; free text is redacted before persistence or export.
- **Honest data.** Missing timing is unavailable, not zero. Collection gaps, expired history and small samples are
  labelled. Sampled traces never feed usage totals.
- **No new infrastructure for the first release.** PostgreSQL and SQLite with targeted projections and indexes. No
  ClickHouse, Elasticsearch, Kafka or analytics database until measured load requires one.

## Terminology: platform tracing vs Insights

- **Platform tracing** is what exists today: Control's Quarkus OpenTelemetry spans reduced by `TraceRedaction`,
  exported only when `toolgate.control.trace-export-enabled` is true. It is unaffected by Insights mode.
- **Insights** is everything this plan adds: invocation/attempt summaries, admission counters, aggregates, tool
  execution spans, the local span store and Insights metric/trace export. Every requirement about modes below refers to
  Insights only.

## Current state

| Area | What exists | Gap |
| --- | --- | --- |
| Gateway correlation | [http.rs](apps/gateway/src/http.rs) validates an incoming `traceparent`, generates `gw-…` request IDs and a trace ID | Without an incoming parent the generated `traceparent` has sampled flag `00`; `tracestate` is dropped |
| Tool input | `ToolInput.traceparent` exists ([v2.rs](packages/contracts/rust/src/v2.rs)) | [server.rs](apps/tool-host/src/server.rs) and [execution/mod.rs](apps/endpoint-client/src/execution/mod.rs) pass `traceparent: None`; conformance case `mcp-core/trace-propagation` is `authored` and fails today |
| Control tracing | `TraceRedaction` reduces spans to trace ID, timing, fixed name, HTTP method/status, request ID; export off by default | No tool-execution spans; metrics and logs export disabled |
| Execution record | `control_enterprise_invocations` with `revision`, ledger states and task projection ([invocation-state-v2.md](docs/control-plane/invocation-state-v2.md)); ordered, deduplicated run events per attempt ([run-events.md](docs/control-plane/run-events.md)) | No aggregates, no cross-invocation query; run events kept 7 days |
| Outbox precedent | `control_authorization_outbox` (`tenant_id`, `revision`, `occurred_at`, `published_at`) written in the state transaction | No Insights outbox |
| Gateway metrics | Process counters including `block`, 6-bucket duration histogram ([limits.rs](apps/gateway/src/limits.rs)) | Not per tenant/tool/decision; gateway denials leave no per-invocation record |
| Request IDs | Gateway `gw-…` request ID; Control's server-generated `Correlation` UUID | Two distinct request IDs; neither links to the other |

Constraints that shape the design:

- **Single writer.** Only Control writes the state database (invocation-state-v2 §1). Gateway, Tool Host and endpoint
  client send Insights data to Control; they never persist it.
- **Two databases.** Control runs on PostgreSQL and SQLite (single serialized writer); every table, migration and
  query must work on both.
- **Strict decoders.** `ToolInput` is `deny_unknown_fields` in Rust and the PHP binding rejects unknown keys. New wire
  fields need protocol-version negotiation.
- **Outcome can change once.** Terminal outcomes are final except `OUTCOME_UNKNOWN → COMPLETED | FAILED` (T13), so
  projections must be able to move an invocation between outcome buckets.
- **Pre-ledger work.** `ACCEPTED` lives only in the gateway spool, and authentication/authorization denials can happen
  before any invocation row exists.
- **History in the invocation row is lossy.** `task_document.transitions` is capped at 64 entries, and run events cap
  at 1,000 events / 1 MiB per invocation and expire after 7 days. Neither is a guaranteed source for every
  historical summary field.
- **Revisions are not all transitions.** Lease renewal (T6) bumps the revision every lease/3 without changing state.
  Automatic re-queue of non-ledger tools after lease loss (T11, attempt < 3) creates new attempts.

## 1. Identity and correlation

### Hierarchy

```
session_id (caller-declared, optional)
└── agent_run_id (caller-declared, optional)
    └── invocation  ── invocation_id (Toolgate, authoritative), tool_call_id (caller reference)
        └── attempt ── execution_id (Toolgate, one per fenced attempt), attempt_number
trace_id / span_id: observability only; one invocation may span several traces
```

| Field | Meaning | Source | Trust |
| --- | --- | --- | --- |
| `tenant_id` | Owning tenant | Authenticated context | Verified |
| `client_id` | Calling application | Gateway credential | Verified |
| `user_id` | User the call runs for | Authenticated context; absent for service calls | Verified |
| `invocation_id` | One admitted invocation | Control (existing `invocationId`) | Authoritative |
| `execution_id` | One fenced execution attempt | Control, bound to the permit nonce / fencing token | Authoritative |
| `tool_call_id` | Caller's logical call reference | Caller; generated if absent | Declared |
| `session_id`, `agent_id`, `agent_run_id` | Workflow context | Caller metadata (`Mcp-Session-Id` is a transport session, not this) | Declared |
| `parent_invocation_id` | Nested call (later phase) | Propagated context, validated to be same tenant | Declared |
| `trace_id`, `span_id` | Distributed trace | W3C trace context or generated | Untrusted input |
| `request_id`, `control_request_id` | Transport request at gateway / Control | Existing generators | Authoritative |

Every stored declared field records its provenance (`verified`, `declared`, `generated`, `absent`).

### Invariants

- `invocation_id` and `execution_id` are generated by Toolgate and are the only business identities. Trace and span
  IDs never identify business state.
- `tool_call_id` is a correlation reference, not a uniqueness key or credential. Duplicate submission is detected by
  the existing idempotency key and request digest, not by `tool_call_id`.
- A caller reusing a `tool_call_id` in a new request creates a new invocation; both are searchable under that ID.
- Each attempt admitted under a new permit/fencing token gets a new `execution_id` and the next `attempt_number`. A
  reconnect under the same fencing token is the same attempt.
- Declared metadata and trace context never influence authentication, routing, policy or approval. Absent values are
  recorded as absent, not invented.
- step 1 must confirm, against the M5 dedup and reconciliation work, exactly which ledger transitions create a new
  permit (re-dispatch after lease loss, reconciliation, manual retry) and therefore a new attempt.

## 2. Propagation

1. Define a shared execution-context contract (wire names, length bounds) in the canonical schemas and regenerate all
   bindings and fixtures. Add it to `ToolInput` only under a negotiated protocol version.
2. **Old peers:** a peer that does not negotiate the new version receives today's shape. The loss is recorded: the
   attempt summary marks `context_delivered = false` and the peer's protocol version, so missing context is visible
   rather than silent. `traceparent` (already in the contract) is always sent.
3. Ingress carriers: HTTP headers (`traceparent`, `tracestate`, documented `Toolgate-*` context headers) and MCP
   `_meta`. Context never travels in tool arguments. Malformed, duplicate or oversized values are dropped and counted.
4. **Trust boundary for trace context:** the gateway validates format and length, keeps the caller's trace ID as the
   parent for correlation, and never uses `tracestate` or baggage for any decision. Baggage is not propagated.
5. Gateway: preserve bounded `tracestate`; set the sampled flag from the configured policy (§5) instead of `00`.
6. Carry context through authorization, the invocation row, dispatch, queue, Tool Host, endpoint client and
   `ToolInput`. Fix the two `traceparent: None` sites first and make `mcp-core/trace-propagation` pass.
7. SDKs expose context read-only and offer a helper to propagate `traceparent` downstream. They never forward user or
   session IDs automatically.
8. Success and error responses carry a correlation reference (request ID plus invocation ID) that operators can search.

## 3. Collection modes

One deployment setting, default `NONE`:

```dotenv
TOOLGATE_INSIGHTS_MODE=NONE   # NONE | BASIC | DETAILED; invalid values fail startup
```

| Mode | Insights collection | Insights export when configured |
| --- | --- | --- |
| `NONE` | Nothing: no transition facts, checkpoints, summaries, counters, aggregates or Insights spans | None |
| `BASIC` | Invocation and attempt summaries, admission counters, aggregates | Aggregate metrics |
| `DETAILED` | `BASIC` plus tool execution spans in a bounded local span store | Metrics plus retained traces |

- Platform tracing behaves the same in every mode.
- Context propagation and mandatory IDs work in every mode.
- Summaries and aggregates are identical in `BASIC` and `DETAILED`; usage counts never depend on mode or sampling.
- Mode changes apply to new transitions after restart or rollout. Collection windows are recorded so the UI shows gaps.
  Invocations already in flight when `BASIC` starts are projected from their next transition and marked
  `partial_coverage`.
- Optional `TOOLGATE_INSIGHTS_EXCLUDED_TENANTS` (deferred until a contract needs it) excludes tenants from `DETAILED`.

## 4. BASIC: projections

### Transition facts: the projection feed

The transactional outbox is the authoritative and only projection feed. It carries immutable facts, so the projector
never has to infer history from the invocation's current state.

- Every **state-changing** transition (T1–T5, T7–T13; not T6 lease renewal) inserts one row into
  `insights_transition_fact` in the same transaction, following the `control_authorization_outbox` pattern:

  | Column | Content |
  | --- | --- |
  | `tenant_id`, `invocation_id`, `fact_seq` | Primary key. `fact_seq` is per invocation and gap-free (see below) |
  | `revision` | Invocation revision written by this transaction |
  | `transition`, `from_state`, `to_state` | Transition number and states |
  | `occurred_at` | Control commit time |
  | `execution_id`, `attempt_number` | When the transition starts, reports or ends an attempt |
  | `outcome`, `error_category` | On terminal transitions and T13 |
  | `executor_duration_ms`, `executor_kind`, `executor_id`, `peer_protocol_version`, `context_delivered` | On result reports |
  | `context` | On T1 only: bounded correlation fields and provenance from §1 |
  | `projected_at` | Null until projected |

- **Sequence.** A new `insights_fact_seq` column on `control_enterprise_invocations` (appended migration, default 0)
  holds the last issued sequence. Every state-changing transition already updates that row under an expected
  revision, so the same `UPDATE` increments `insights_fact_seq` and the fact is inserted with the new value. No
  historical facts are read to allocate a sequence, and the expected-revision check already serialises concurrent
  transitions on one invocation on both databases.
- **Atomic capture.** In `BASIC` and `DETAILED` the fact insert is part of the ledger transaction and shares its fate:
  if the insert fails, the transition fails and is retried under Control's existing transaction retry policy, exactly
  like a failure of the audit write in that transaction. There is no fail-open path or savepoint. Rationale: the
  insert is a fixed-size row into an append-only table with no constraints beyond its primary key, so the failures
  that can stop it (disk full, lost connection, database down) stop the ledger write too; a fail-open path would add
  PostgreSQL savepoint handling and a weaker completeness guarantee to protect against almost nothing. The storage
  quota is enforced by the projector and retention jobs, never by rejecting fact inserts. The overhead of the insert
  and the counter increment is held to the step 1 budget and measured in Stage 7 (early benchmark in the step 3 PR); if it is over budget the capture
  mechanism is redesigned (below), not made best-effort. In `NONE` neither the column nor the table is written.
- **Every summary field has a fact as its source.** step 1 completes a field-to-fact table for both summaries; a
  field without a fact source is not shipped.
- **Ordering and race freedom.** The projector selects rows with `projected_at IS NULL`, not a timestamp cursor, so a
  row that commits late is still picked up. Per invocation it applies facts strictly in `fact_seq` order; if
  `fact_seq` `n` is present but `n − 1` is not yet projected, it waits for it. A gap that persists past a timeout (a
  bug, never expected) marks the invocation `projection_gap` and is reported.
- **No equivalent fallback.** A scan of the invocation table by timestamp or revision misses intermediate transitions
  and is not an acceptable substitute. If the fact insert exceeds its budget, the capture mechanism is
  redesigned around another durable, ordered, transactional change record (for example an uncapped, prunable
  transition log already written by the ledger), re-tested in Stage 7.
- **Projector execution.**
  - Workers fetch unprojected facts in batches (default 500 facts) ordered by `(tenant_id, invocation_id, fact_seq)`.
  - Work is split into a fixed number of partitions by `hash(tenant_id, invocation_id)` (default 16). All facts for
    one invocation belong to one partition, so ordering is kept without serialising the installation.
  - **Ownership across Control replicas.** `insights_projector_lease(partition, owner, epoch, expires_at)` assigns
    each partition to one worker. A worker claims or renews a lease with a conditional update that bumps `epoch`,
    and every batch transaction re-checks `owner` and `epoch` on the lease row before committing, so a worker that
    lost its lease (pause, network split) cannot commit. Workers per replica are bounded (default 4 on PostgreSQL;
    SQLite installations run one Control process and one worker).
  - Exclusivity is for efficiency, not correctness: each batch also locks the checkpoint rows it changes and
    re-reads `last_fact_seq` inside the transaction, so even two workers on one partition cannot apply a fact twice.
  - Within a batch a worker folds every fact for an invocation into one net contribution change. It then sums those
    changes per aggregate cell across the whole batch, and writes summaries, attempt rows, checkpoints, cell deltas
    and `projected_at` in one transaction. A batch therefore writes each touched cell once, not once per fact.
  - Cell upserts are applied in sorted key order so concurrent PostgreSQL workers cannot deadlock on shared cells.
  - Write amplification (rows written per fact) is measured in Stage 7 alongside lag.
- Projector lag, batch size and backlog are bounded and reported. If the backlog exceeds its bound the status endpoint
  reports lag and the UI marks recent buckets incomplete; the execution path is never throttled.
- **Fact retention is short.** Facts exist to feed the projector and to rebuild recent summaries, not as history.
  A fact is deleted once it is projected, its invocation is final (below), and the fact retention window has passed
  since the invocation became final (default 7 days, configurable). Summaries hold history after that. Facts carry
  only the primary key and a partial index on unprojected rows; no correlation indexes.

### Idempotency and correctness

- `insights_projection_checkpoint(tenant_id, invocation_id, last_fact_seq, contribution, final)` is the minimal
  durable state for correct aggregates. `contribution` is the exact set of aggregate cells and histogram buckets the
  invocation currently adds, at every granularity (§ Aggregates). It is written for every projected invocation
  regardless of summary retention or quota.
- **Final** means terminal in a state other than `OUTCOME_UNKNOWN`; no further fact (including T13) can arrive for
  a final invocation.
- **Checkpoint lifecycle.** A checkpoint lives exactly as long as its invocation's facts. One retention transaction
  deletes a final invocation's facts and its checkpoint together, and only when every fact is projected. So:
  - a fact can never exist without its checkpoint, and the projector can never see an already-applied fact as new;
  - projected facts are never re-selected (`projected_at` is set in the same transaction that applies them);
  - after deletion, the invocation is final, so nothing can arrive that would need the old contribution.
- Applying a fact with `fact_seq <= last_fact_seq` is a no-op. Otherwise the projector computes the new contribution,
  subtracts the old one, adds the new one, and advances the checkpoint, all in one transaction. This handles replay,
  crash mid-batch, late completions, automatic re-queue (T11) and the T13 outcome change.
- **Rebuild** is two-level, and writes into shadow tables that replace the live rows per bucket only after
  comparison:
  - within fact retention (7 days after final): facts → summaries, attempts and checkpoints, exact;
  - within summary retention (30 days): summaries → aggregate cells, exact, because every aggregate input (bucket
    time, dimensions, outcome, durations) is a summary column;
  - beyond summary retention: aggregates cannot be rebuilt and are reported as such.
- **Reconciliation** runs on a schedule:
  - per invocation within fact retention: final state, outcome, attempt count, `execution_id`s, terminal and stage
    timestamps against the invocation row, `task_document.transitions` and run-event `STATE` events where those
    still hold the data;
  - per aggregate cell within summary retention: cell values against a recomputation from summaries (this does not
    depend on checkpoints still existing);
  - each reconciled bucket records `reconciled_at` and its result, so older buckets show when they were last proven
    correct. Drift is reported with the affected invocations or buckets.

### Projections

`insights_invocation_summary` (one per invocation): IDs and provenance from §1, tool ID/version, runtime, client,
effect safety, final outcome, error category, attempt count, timestamps for accepted, approval requested/resolved,
queued, first start, terminal; derived durations.

`insights_attempt_summary` (one per attempt): `execution_id`, `invocation_id`, `attempt_number`, executor kind
(Tool Host or endpoint client), executor/device identity, peer protocol version, `context_delivered`, start/end
timestamps, attempt outcome, executor-measured duration, trace/span references.

`insights_admission_counter`: admission decisions that may not create an invocation row, by minute bucket, tenant,
client, tool and category (`UNAUTHENTICATED`, `POLICY_DENIED`, `APPROVAL_REQUIRED`, `RATE_LIMITED`, `KILL_SWITCH`,
`INVALID_REQUEST`). Ledger-derived metrics never claim to include these; the UI reports them separately.

Admission counters count **requests**, not logical calls: a caller retrying a denied request is counted each time.
Completeness depends on where the decision is made:

- **Control-side decisions** (policy at T1, kill switch, ASK) are counted by Control in the admission transaction or
  immediately after it, and are complete.
- **Gateway-side decisions** (authentication, rate limiting, request validation) are **best-effort**:
  - Each gateway process generates a random `epoch` at start. It sends **delta batches** numbered `seq = 1, 2, …`
    within that epoch, each covering closed minute buckets.
  - Control stores `(gateway_id, epoch, seq)` and ignores duplicates. The gateway retries an unacknowledged batch with
    the same `seq` and bounded memory; if its retry buffer overflows it drops the oldest batch and reports the
    dropped request count in the next batch.
  - Control records each epoch's first and last seen `seq` and last-seen time. A missing `seq`, or an epoch that stops
    without its process having sent a final flush on graceful shutdown, is a **source gap**: the affected gateway and
    interval are shown in the UI, with the maximum possible loss (one flush interval, default 10 s, of that gateway's
    traffic, plus reported drops).
- step 1 checks whether gateway denials are already durably audited. If they are, gateway-side counters are derived
  from that audit record instead and become complete.

### Timing

| Measure | Definition | Clock |
| --- | --- | --- |
| Approval latency | approval resolved − approval requested | Control |
| Queue latency | first start − queued | Control |
| Attempt execution | attempt end − attempt start | Executor monotonic clock, reported with the result |
| End-to-end | terminal − accepted (includes approvals and retries) | Control |

Control timestamps come from the Control process that commits the transition. With several Control replicas they are
subject to clock skew: replicas must run time synchronisation, and a negative derived duration is stored as
unavailable. Durations across machines are never computed from executor timestamps.

### Aggregates

- Rollups (UTC, half-open buckets) with counts and a mergeable duration histogram with fixed bucket bounds:
  - `by_tool`: tenant, tool, runtime, outcome.
  - `by_client`: tenant, client, outcome.
- Granularity: 1-minute buckets kept 48 hours, hourly kept 90 days, daily kept 13 months.
- **No compaction between granularities.** The projector writes every contribution change directly to the minute,
  hour and day cells at once, and the checkpoint records all three. Coarser rollups are never derived from finer
  ones, so a late correction (T13, a late terminal fact) updates every granularity still retained and nothing has to
  be reopened. When a correction targets a cell that retention has already deleted (for example a minute bucket older
  than 48 hours), that granularity is skipped and the coarser retained cells are still corrected.
- Retention deletes whole buckets per granularity. It never deletes a cell that a non-final checkpoint still refers
  to; such cells are kept until the invocation is final, then removed by the next retention batch.
- Rebuild of a range (§ Idempotency) recomputes all three granularities from summaries for that range and replaces
  the cells atomically per bucket.
- Filters that combine dimensions not in one rollup (for example client × tool) are answered from summaries and are
  available only within summary retention; the UI says so.
- Percentiles come from merged histograms, never from averaging percentiles. Every percentile shows its sample size.
  p95 is hidden below 20 samples and p99 below 100.

### Retention and storage

- Defaults: transition facts 7 days after the invocation is final, summaries 30 days, aggregates as above, admission
  counters as aggregates, span store 72 hours. All configurable; fact retention cannot exceed summary retention.
- Distinct users, sessions and runs are computed from summaries, so beyond summary retention they show as
  **unavailable for this range**, never zero. Long-term distinct-count sketches are deferred.
- Retention deletes run in bounded batches.
- **Storage quota** (per installation) applies to summaries, transition facts of final invocations and the span store.
  It never applies to facts or checkpoints of non-final invocations, which are what keep aggregates correct. When the
  quota is exceeded:
  - new summary rows stop, and the fact retention window drops to zero: a final invocation's facts and checkpoint
    are deleted together as soon as all its facts are projected;
  - aggregates and counters continue and stay exact, because every invocation that can still change keeps its
    checkpoint;
  - the affected range is marked `summaries_unavailable`: drill-down, cross-dimension filters, distinct counts,
    rebuild and cell reconciliation show as unavailable there, and an operator alert is raised.
- Fact capture is atomic with the ledger (§ Transition facts), so there is no "fact missing" state to flag. A
  projector that cannot write (storage failure) stops and reports lag; facts wait, and nothing is lost.
- Schema: new tables plus the `insights_fact_seq` column, as appended migrations for both databases. Index
  `(tenant_id, started_at)`, `(started_at)`, each correlation ID, and facts `(projected_at)` partial/filtered where
  supported.

### Metric definitions

| Metric | Definition |
| --- | --- |
| Logical calls | Admitted invocations |
| Attempts | Execution attempts started |
| Retry rate | Invocations with more than one attempt ÷ invocations with at least one attempt |
| Success rate | `COMPLETED` ÷ (`COMPLETED` + `FAILED`). `PARTIAL`, cancelled, expired and outcome-unknown are shown as separate series, outside the denominator |
| Execution failures | Terminal `FAILED`, split by error category |
| Admission denials | Sum of admission counter categories other than `APPROVAL_REQUIRED` |
| Unknown outcomes | Invocations currently in `OUTCOME_UNKNOWN` |

Error categories: `POLICY`, `TIMEOUT`, `CANCELLED`, `TOOL_ERROR`, `INFRASTRUCTURE` (dispatch, executor or dependency
failure), `PARTIAL`, `UNKNOWN`. step 1 maps every existing error code to exactly one category.

Operator health metrics, also from projections: queue depth and oldest pending age, dispatch failure rate, retry
amplification by tool and runtime, Tool Host versus endpoint-client latency, invocations stuck past their expected
lifecycle, projector lag and rebuild progress, exporter drops and queue utilisation, Insights storage use.

## 5. DETAILED: spans

### Producing spans

- Spans: gateway ingress and authorization, Control admission/approval/dispatch, Tool Host and endpoint-client
  attempt execution, brokered egress calls, terminal handling. Parent-child for direct paths; span links across
  asynchronous hops. Every component creates its own span ID.
- Attributes come from an allowlist: correlation IDs from §1, tool ID/version, runtime, outcome, error category,
  service, environment. Error text, URLs, headers and resource locators are redacted at the producer and again in
  Control; if redaction fails the attribute is dropped.
- Map to OpenTelemetry GenAI conventions where they fit (`execute_tool` spans, `gen_ai.tool.name`,
  `gen_ai.tool.call.id`), verified against the spec at implementation time. Toolgate does not report model tokens or
  cost.
- Metrics use bounded labels only. User, session, call, execution and trace IDs are span/log attributes, never labels.
- Structured logs include trace ID, span ID, request ID and invocation ID.

### Span sink and sampling

Control is the span sink for Insights spans, because it is the only component that knows the final outcome:

1. Gateway, Tool Host and endpoint client post spans for an invocation to Control in bounded batches (the same
   transport and authentication as run events and admission counters). Upload failure drops spans and counts the drop.
2. Control buffers spans per invocation in memory. Memory is bounded by a **global byte budget** (default 64 MiB per
   replica) plus per-invocation span and byte caps, so total memory does not grow with concurrency or run time.
3. **Retention decision (tail sampling).** The decision is a deterministic function of the invocation's outcome,
   elapsed time and `hash(invocation_id)`: keep failed, partial, unknown, cancelled-after-start and slow invocations
   (above a per-tool latency threshold); keep the rest when the hash falls under the configured ratio. Because it is
   deterministic, every Control replica holding spans for the invocation reaches the same decision independently.
   Replicas learn terminal state with a batched lookup of their buffered invocations every few seconds.
4. **Early decisions keep the buffer small.**
   - Once an invocation's elapsed time passes its slow threshold it is already "keep", so its buffer is flushed to
     the span store and later spans are written straight through. Long-running executions therefore do not sit in
     memory.
   - When the global budget is under pressure, the oldest buffers are decided early by the hash alone: kept buffers
     are flushed, others are discarded and the invocation is marked `spans_dropped_early`. A failure that happens after
     an early discard has no spans; this is counted and shown.
   - A buffer whose invocation passes its deadline plus a grace period without a terminal state is flushed as
     incomplete.
5. Retained spans go to the local span store and, if configured, to OTLP export.

Head sampling at the gateway only decides whether the caller's upstream sampled flag is honoured for external traces;
it never decides whether Control keeps local spans. A caller's unsampled parent does not suppress local retention.

The alternative, tail sampling in an OpenTelemetry Collector, is rejected for the first release: it makes the local
waterfall depend on a Collector and duplicates the outcome logic Control already has. If Control's measured span load
exceeds its budget in Stage 7, move the sink into a separate Control-owned process with the same contract.

### Endpoint and host span integrity

- Span uploads are authenticated with the device or host certificate and accepted only for invocations placed on that
  device and under its fencing token, like run events.
- Spans from an endpoint client are stored with `source = endpoint` and cannot claim gateway or Control span names or
  service identity; Control rewrites the resource attributes from the authenticated identity.
- Uploads are rate-limited and size-bounded per device and per invocation.

### Local span store

- Bounded, redacted, short retention (default 72 hours), with per-invocation and per-installation caps. Counts toward
  the storage quota.
- Feeds the waterfall in the detail window without any external backend.

## 6. Export

- Control is the single Insights exporter: it exports aggregate metrics (BASIC) and retained traces (DETAILED) over
  OTLP to one configured endpoint, reusing the Helm `tracing` settings. Gateway, Tool Host and endpoint client do not
  need OTLP exporters for Insights.
- A Collector, when used, only fans out to Datadog, New Relic or both. Ship pinned Collector configurations, Compose
  and Helm examples, and importable dashboards for volume, outcomes, latency percentiles, queue wait, retries and top
  tools. Verify exporter settings against vendor documentation at implementation time.
- Export is asynchronous with bounded queues, retry with backoff and drop counters. An outage never blocks execution,
  projection or local Insights.
- Shared vendor account: `tenant_id` is a span attribute; it is a metric label only if cardinality was measured
  acceptable. Export destinations are operator-configured only and never tenant-selectable.
- External trace links use configured site/account URL templates and appear only on records with retained spans.

## 7. Insights page and APIs

### Access

- Access requires an installation-operator role. step 1 decides whether that is the `SUPER_ADMIN` portal role
  ([ADR 022](docs/adr/022-super-administrator-review-exception.md)) or a dedicated permission; no tenant-scoped
  management role can grant it.
- Insights APIs live under a separate operator path, are absent from the tenant API surface and OpenAPI, and ignore
  any tenant selection that would widen scope. Tenant filters are query inputs checked by the operator role, never
  routing.
- **Operator audit:** cross-tenant searches, detail views, rebuilds and Insights configuration changes are written to
  `control_audit` at installation scope (actor, query filters without free-text values, result count).

### Page

- **States:** `NONE` shows how to enable Insights in deployment configuration and makes no data requests. The page
  loads a status endpoint first (mode, collection windows, projector lag, storage use, exporter status, drop counts);
  if status fails it shows a retryable error, never an empty dataset. Empty, no-match, gap, expired-history, loading
  and partial-coverage states are distinct.
- **Overview:** time range plus filters for tenant, client, tool, runtime and outcome. Logical calls, attempts,
  success rate with its denominator, admission denials, failures by category, p50/p95/p99 with sample sizes.
- **Graphs:** usage, outcomes, tool ranking, latency percentiles, approval vs queue vs execution, retries,
  admission decisions, session/agent activity with coverage for missing IDs. Server-chosen bucket size with timezone
  and interval labelled. Clicking a bucket opens the explorer with matching filters. Accessible tables, keyboard
  support, light/dark themes.
- **Explorer:** search by invocation, tool-call, execution, trace, session, agent-run or request ID; filter by tenant,
  client, user, agent, tool, outcome, runtime, time. Server-side pagination.
- **Detail window:** fixed header with Close, scrolling body, Escape to dismiss, focus restored. Sections: overview,
  identity/correlation with provenance and copyable IDs, stage timeline from summary timestamps (all modes), attempts,
  span waterfall when spans were retained, run events, external trace link. Missing or dropped data is labelled.
- **Sessions** (later phase): timeline grouped by caller-declared session ID, plus `parent_invocation_id` nesting if
  it ships.

## 8. Delivery

### Branching

- All work happens on the long-lived feature branch **`feature/olo-insights`**, cut from `main`.
- Each step is **one PR** into `feature/olo-insights`. Nothing merges to `main` until every step PR has merged (100%)
  and the integration testing stage has passed.
- The feature branch is brought up to date with `main` at the start of each step and before the testing stage, so
  the final merge carries no surprise conflicts.
- `feature/olo-insights` merges to `main` once, as a single final PR, after Stage 7.
- `TOOLGATE_INSIGHTS_MODE` defaults to `NONE` throughout, so the branch behaves like `main` for anyone not enabling
  Insights.

### Testing approach

- **Per step PR:** the repository's standard CI plus focused unit and contract tests for the code in that PR. No
  dedicated test cycle per PR. A step PR must not break existing behaviour with `NONE`.
- **Stage 7, integration testing:** one dedicated test cycle on the complete feature branch that runs every §9
  release gate, the performance and storage benchmarks, failure injection, the mixed-version matrix, vendor smoke
  tests and UI correctness. Defects are fixed by fix-up PRs into the feature branch and the affected gates re-run.
- Trade-off: projection correctness (crash/replay, ordering, quota) is proven only in Stage 7, after the UI and spans
  are built on it. The step 3 PR therefore includes history-fidelity and replay unit tests and an early fact-insert
  benchmark, so the riskiest logic has coverage before later steps depend on it.

### Step PRs

**Work %** is a relative effort estimate for the whole plan (sums to 100%), not a measurement; re-estimate after the
step 1 ADR. **Cumulative %** is the share of the plan complete once that step finishes.

| Step | PR into `feature/olo-insights` | Scope | Done when | Depends on | Work % | Cumulative % |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | Design: ADR for execution correlation and operator Insights | ID mapping and attempt boundaries, context contract and negotiation, transition-fact schema and field-to-fact table, fact-insert overhead budget, retention, projector partitions and leases, span byte budget, histogram buckets, error-code mapping, operator role, overhead budgets | Every field has a source, trust level and owner; budgets are written down for SQLite and PostgreSQL | — | 4% | 4% |
| 2 | Propagation | Context contract with generated bindings and fixtures; gateway `tracestate`, sampled flag, context headers and `_meta`; Tool Host and endpoint client fill `traceparent` and context; SDK accessors and helper; correlation reference in responses | `mcp-core/trace-propagation` passes; old peers report `context_delivered = false` | 1 | 20% | 24% |
| 3 | BASIC | Mode setting, transition facts and `insights_fact_seq`, projector with partitions, leases and checkpoints, invocation and attempt summaries, rollups and histograms, retention and quota, admission counters, rebuild and reconciliation, status/query APIs, operator role check and audit | Projections match the ledger on a known workload; history-fidelity and replay unit tests pass | 1 | 32% | 56% |
| 4 | Insights page | Status-first loading and all page states, overview, graphs with drill-down, explorer, detail window with stage timeline and attempts | Non-operators denied at every Insights API; all page states render | 3 | 12% | 68% |
| 5 | DETAILED and export | Allowlisted span producers and authenticated uploads, Control span buffer and deterministic tail sampling, local span store and waterfall, Control OTLP export, Collector configs, Compose/Helm examples, vendor dashboards and links | Waterfall works with no Collector; spans reach a test OTLP receiver | 2, 4 | 18% | 86% |
| 6 | Sessions | Session timeline, optional `parent_invocation_id` | Sessions group correctly with parallel and nested work | 4 | 4% | 90% |
| 7 | Integration testing (fix-up PRs only) | Full §9 release-gate suite on the complete branch, benchmarks on both databases, failure injection, mixed-version matrix, vendor smoke tests in available accounts, UI review | Every §9 gate passes; measured overheads within budget; unverified vendor accounts listed | 1–6 | 10% | 100% |
| 8 | Final merge: `feature/olo-insights` → `main` | Sync with `main`, re-run CI and a Stage 7 smoke subset, release notes and docs | CI green on the merge result | 7 | — | 100% |

Steps 2 and 3 depend only on the ADR and can be developed in parallel, but merge in order: 2, then 3.

## 9. Release gates

Gates are written alongside the step that builds the feature, and all of them run together in Stage 7. The last
column names the step whose code the gate covers.

| Gate | Acceptance | Covers step |
| --- | --- | --- |
| Correlation | 100% of accepted test invocations have valid `invocation_id` and per-attempt `execution_id` | 2 |
| Compatibility | Every old/new combination of gateway, Tool Host, endpoint client and SDK works; lost context is reported | 2 |
| Retry accounting | Exact logical-call and attempt counts after duplicates, retries, reconnects, crashes and replay | 3 |
| Projection recovery | Killing the projector mid-batch and restarting yields no double counting | 3 |
| History fidelity | An invocation that passes QUEUED → RUNNING → OUTCOME_UNKNOWN → COMPLETED (and a T11 re-queue) before the projector runs gets every stage timestamp, attempt and final outcome right | 3 |
| Ordering | Facts committed out of order across concurrent transactions and projector workers are projected in `fact_seq` order with no gaps or deadlocks | 3 |
| Atomic capture | Injected fact-insert failure aborts and retries the transition; no transition commits without its fact | 3 |
| Late corrections | A T13 outcome change after the minute bucket expired corrects the hour and day cells exactly | 3 |
| Write amplification | Rows written per fact, fact table size at steady state, and projector throughput measured on both databases at peak load | 3 |
| Projector ownership | Two Control replicas with a paused worker that loses its lease: the paused worker's batch is refused, and no fact is applied twice | 3 |
| Checkpoint lifecycle | Retention deleting facts and checkpoints concurrently with projection, and a rebuild after deletion, never double-counts or loses a contribution | 3 |
| Span memory | Thousands of concurrent long-running executions keep span buffer memory within the global budget; early decisions and drops are counted | 5 |
| Reconciliation | Rebuilt summaries and aggregates equal the live projection, including timing and attempt fields, not only counts | 3 |
| Quota | With the quota exceeded, outcome changes and retries still correct aggregates exactly; summary-dependent views show unavailable | 3 |
| Admission counters | Duplicate and retried batches counted once; killed gateway produces a visible source gap with a bounded loss estimate | 3 |
| Database | Migrations, concurrent writers and restart on PostgreSQL and SQLite | 3 |
| Performance | Measured p95/p99 overhead of `BASIC` and `DETAILED` versus `NONE` within the step 1 budgets, for normal and peak concurrency, SQLite and PostgreSQL | 3, 5 |
| `NONE` | No transition facts, checkpoints, summaries, counters, aggregates, Insights spans or Insights exports; platform tracing unchanged | 3 |
| Mode equivalence | `BASIC` and `DETAILED` produce identical counts for the same workload | 5 |
| Tenant isolation | Non-operators denied at every Insights API; no Insights data on tenant APIs, headers, errors or exports | 4 |
| PII leakage | Canary secrets in error text, URLs and metadata absent from summaries, spans, exports, logs and API responses | 3, 5 |
| Span integrity | An endpoint cannot upload spans for another device's invocation or impersonate gateway/Control spans | 5 |
| Trace coverage | Within the span budget and with no upload failures, direct, asynchronous, retry, reconnect, timeout and error invocations produce connected, retained traces | 5 |
| Failure injection | Collector outage, disk pressure, DB contention and telemetry flood never affect execution; drops and lag are reported | 5 |
| UI correctness | Missing data, gaps, zero traffic, expired history, small samples and partial coverage visibly distinct | 4 |

## 10. Acceptance criteria

Each criterion states what is guaranteed and what is best-effort. "Verified" means demonstrated by the §9 gates on
the stated workloads, not proven for every possible load.

1. Every invocation has an authoritative `invocation_id`, every attempt a unique `execution_id`, and caller references
   are stored with provenance. *Guaranteed.*
2. Authenticated identity and declared session/agent context reach every peer that negotiates the new protocol
   version; for older peers, the loss is recorded on the attempt. *Guaranteed.*
3. Within summary retention, an operator can find an invocation by any stored correlation reference and see outcome,
   attempts and stage timing. In `DETAILED`, a span waterfall is shown when spans were retained, without an external
   backend; when they were not, the reason (sampled out, dropped early, upload failure, expired) is shown.
4. Retained spans and structured logs for an execution carry the same trace ID and invocation ID. Unsampled
   executions have logs only.
5. `TOOLGATE_INSIGHTS_MODE` accepts exactly `NONE`, `BASIC`, `DETAILED`, defaulting to `NONE`; in `NONE` Insights
   writes and exports nothing, and platform tracing is unchanged. *Guaranteed.*
6. In `BASIC`/`DETAILED` no state-changing transition commits without its fact. *Guaranteed.* Projections are
   idempotent and, once the projector has caught up, match the ledger including timing and attempts. *Verified* on
   the Stage 7 workloads. Rebuild is available within fact retention (summaries) and summary retention (aggregates).
7. Control-side admission decisions are complete; gateway-side admission counters are best-effort, labelled as such,
   and show source gaps with a loss bound. Neither is mixed into ledger-derived outcomes.
8. Insights data is served only to the installation-operator role, and operator access is audited. *Guaranteed.*
9. Graphs cover the selected range from aggregates, support drill-down, show sample sizes, projector lag and
   reconciliation status, and distinguish zero activity from gaps, incomplete recent buckets and expired history.
10. `DETAILED` keeps failed and slow traces whenever their spans reached Control and fit the span budget; losses are
    counted and shown. Arguments, outputs and secrets are never captured. *The exclusion is guaranteed; retention is
    best-effort within stated bounds.*
11. OTLP export works without a specific vendor; Datadog and New Relic each work alone or together through a Collector,
    and neither is required for built-in Insights. *Verified* in the vendor accounts available; others are reported
    as unverified.
12. Projector, span-pipeline and exporter failures never fail or delay tool execution. *Guaranteed.* The fact insert
    is the one deliberate coupling: it fails with the ledger transaction it belongs to, and its added commit latency
    stays within the step 1 budget on the Stage 7 workloads. *Verified.*
