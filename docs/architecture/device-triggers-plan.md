# OLO ToolGate — Triggers (v1 plan)

**Status:** Draft for design review, revision 12. Supersedes "Governed Event Fabric and Registered AI Workflows" for v1.
**Performance:** **v1 has no performance check.** No load, throughput, latency or capacity target is a v1 gate or
claim (section 20.1). Bounded **resource-safety** tests, which are functional and have no rate targets, are part of the
gate (section 20.2).
**Fresh build:** **unreleased; one production code path.** No backward compatibility, no database migrations and no
legacy or outdated code (section 0).
**Baseline:** `main` at `bb3b078d`, which includes the #230 deliveries through stage S21.
**Proposed ADR:** ADR 058 *Triggers* (amends ADR 013, ADR 014, ADR 027 and the Tool SDK authoring contract, so it
reopens gate D0). ADR 032 and ADR 057 are already taken (*Renewable execution authority*, *Generalized target fencing*).
**Date:** 2026-11-06

**Changes in revision 12** (fresh build and #230 alignment)

- **Section 0: fresh-build rule.** Triggers are unreleased. There is no backward compatibility, no database migration
  and no legacy or outdated code: one production code path per behavior. Tables, including Quartz's, go into the
  single fresh initial schema. Feature flags are removed once their module's slice checkpoint passes.
- **ADR number** changed from 032 (taken) to 058, the next free number on `main`.
- **Actions reuse the #230 operation and effect ledger** (14.3, 14.4, M10, module 14). The plan no longer builds its own
  `trigger_action_operations` and `trigger_action_effects` tables. Operations, attempts, effect identities, consent,
  resend and reconciliation come from ADR 034–036 and 053–057 and the S10 operation finalizer. Triggers add
  only a thin delivery-to-operation mapping and the repeat approval record.
- **Effect rules follow delivered #230 behavior** (14.4): financial and destructive effects always need exact consent
  (ADR 055), so the tenant `unattendedEffects` opt-in no longer covers them. A retry after `OUTCOME_UNKNOWN` without
  reconciliation needs a current reviewed target guarantee (ADR 056), not just a declared idempotency.
- **Parallel tracks** (24.1, 24.5): modules that don't share code may run at the same time, with one schema owner and
  explicit coordination points with #230 stages S22, S23, S27–S28, S30 and S31.

**Changes in revision 11** (implementation plan)

- **Section 24: implementation plan, one module at a time.** 18 modules built strictly in sequence, each with:
  - an **effort percentage** (its share of total v1 effort including its own tests; the total is 100%);
  - dependencies and a *Done when*;
  - the M12 scenarios it owns.

  Tests are written once, at the cheapest layer, with end-to-end runs only at four slice checkpoints (35%, 56%, 85%,
  92%). Finished modules are trusted black boxes behind feature flags, and the final module re-runs the full gate once.

**Changes in revision 10** (sixth design review)

- **P1 — Snapshot paging consistency** (8.5):
  - frozen snapshots with a persisted `snapshotGeneration` and an overall digest;
  - per-page generation, digest and ack;
  - a restart from page 1 when a snapshot is superseded, with a bounded number of supersessions to guarantee progress;
  - Control applies only a complete, verified, newer generation, atomically.
- **P1 — Checkpoint uploads across lease changes** (8.6): uploads are bound to the lease that started them. A lease
  change at any point, including between the last chunk and the commit, abandons the upload without advancing
  Control's checkpoint. The next authorized executor starts from the last committed `ACCEPTED` checkpoint, and uploads
  are never resumed across epochs.
- **P1 — Testable memory boundaries** (20.2): explicit queue and buffer accounting is the primary, deterministic
  invariant. A process-memory ceiling (no OOM or `OutOfMemoryError` under fixed limits) is a secondary safety net.
  There are no heap-delta or GC-dependent assertions.

**Changes in revision 9** (fifth design review)

- **P0 — Retry vs intentional repeat** (14.3, 14.4, 15.4, 10.2):
  - a permanent `operationKey` for history, which is never sent as an idempotency key;
  - `effectId`, stable across retries of one intended effect and sent as the business and idempotency key;
  - `repeatId`, a new, approved intended effect that gets a new `effectId`;
  - `attemptId`, one per execution attempt.

  A new `:retry` route reuses the `effectId`; `:repeat` creates a new one. Composite child keys derive from the
  parent's `effectId`.
- **P1 — Check-in size** (8.2, 8.5, 8.6, 5.6):
  - records what `main` enforces today: no check-in byte limit beyond the global 65 MiB HTTP limit, and a 1 MiB Tool
    Host reply cap;
  - explicit request and ack budgets, snapshot paging, and checkpoint metadata in check-ins;
  - a chunked, authenticated checkpoint transfer whose body, digest and version commit atomically;
  - a worst-case serialization contract test;
  - `SnapshotState` keys use a fixed 32-byte encoding, with build-time and runtime checks on the encoded size.
- **P1 — Bounded resource-safety tests** (20.2): functional tests with no rate targets. They check that full queues,
  exhausted pools and slow subscribers don't bypass limits, deadlock admission or grow memory without bound. They are
  part of the M12 gate.
- **P2 — SDK samples and fixtures** (5.5, M2): the Python file watcher now matches the Java semantics. Shared Java and
  Python conformance fixtures cover incomplete listings, identity changes, snapshot overflow, checkpoints after
  restart, callback disorder and non-monotonic positions.

**Changes in revision 8** (fourth design review)

- **No performance check in v1** (20.1, 21): G-PERF is removed. The release gates are G-STATE, G-IFC and the M12
  scenarios. The bulkheads remain a design requirement, verified functionally. The former targets become post-v1
  qualification targets with a versioned workload fixture (payload size, fan-out, classification complexity, database
  configuration, filter count). A missed target is remedied from bottleneck evidence (tuning first), not by assuming
  deployment separation.
- **P0 — Lease change recovery** (6.6.1): an explicit failover contract for records that are locally durable but not
  accepted:
  - fenced records write no dedupe keys, so they cannot suppress replay;
  - the new host cannot advance past the first missing record;
  - compromise revocation is distinguished from failover, with `distrustFrom` quarantine (T15), and queued records
    from a compromised producer are never trusted.
- **P0 — Replay boundaries** (14.2, 14.3, 10.2):
  - ages use Control's trusted `firstObservedAt`, never source `time`;
  - explicit rules for previously seen and previously unseen late events;
  - ledger retention also covers the source's redelivery horizon;
  - the operation key no longer changes with the action revision; **re-drive** and **repeat** are separate,
    separately authorized operations, and a repeat needs a reason, reconciliation (or attestation) and, for
    high-impact domains, maker/checker approval.
- **P1 — State-machine coverage** (6.6.1): modelled transitions for outbox pruning (T10), retention expiry (T11),
  checkpoint migration (T12), removal (T13), reinstallation (T14) and quarantine (T15), and a test that a replayed event
  gets new process and outbox sequences with the same `id`.
- **P1 — Classification defaults and side channels** (12.2, 12.3): tenant-configurable fail-closed defaults per
  integration kind (`CONFIDENTIAL` for MCP proxy and external integrations), and an enforced-versus-residual
  side-channel table with leak fixtures.
- **P2 — File snapshot contract** (5.4, 5.6): exact version keys (no hashes), capacity tied to the files area's
  `maxEntries`, no pruning on a partial listing, explicit overflow to durable rescan mode, and events documented as
  snapshots, never as history.

**Changes in revision 7** (third design review)

- **P0 — Executable state machine** (6.6.1):
  - per-event states across SDK, supervisor and Control;
  - transitions T1–T9, each with one atomic persistence step and invariant;
  - required crash cases, and lease-epoch drain versus fencing;
  - documented unrecoverable gaps for lost hosts running `NON_REPLAYABLE` triggers;
  - gate **G-STATE**: a TLA+ model check, stateful property tests and a deterministic simulation.
- **P0 — Durable business-operation identity** (14.3): an operation key derived from source identity, and a
  tenant-scoped operation ledger that stays authoritative after event, delivery and result purge. Its retention is set
  by the target's effect class (indefinite for non-idempotent, financial, infrastructure and administrative effects),
  with an age guard against late replays.
- **P0 — Information flow at real tool boundaries** (12.3): labels computed only in Control by a strict schema walk
  over actual values; arrays, maps, optional fields, `oneOf`, unlabelled and MCP proxy outputs defined; no downgrade by
  a signed schema; admin floors; a conformance and adversarial corpus. Gate **G-IFC**, with a safe coarse-mode
  fallback.
- **P1 — Source identity:**
  - IMAP: ids include account, mailbox, `UIDVALIDITY` and UID; callbacks are only hints, and reconcile reads explicitly
    sorted UID ranges.
  - Files: stable version keys (file ID, size, mtime), coalescing acknowledged, and reported as `STATE_SNAPSHOT`.
  - Positions must be monotonic (5.3, 5.4, 5.6).
- **P1 — Authorization changes during running operations** (14.4.1): cancellation, capability revocation and
  target-side fencing are separate mechanisms with stated limits, set out per change type and stage, and the #230
  boundary is explicit.
- **P1 — Capacity and isolation** (20.1): separate DB pools, transaction timeouts, per-subsystem executors with a shed
  order, socket memory caps, per-tenant shares, and load-test targets. (Revision 8 removes the gate: **no performance
  check in v1**; the targets become later qualification targets.) Gate **G-PERF**, whose failure moves workers out
  of Control rather than waiving the target.

**Changes in revision 6** (second design review)

- **P0 — Rejections and checkpoints** (6.6, 8.3):
  - emit outcomes are `LOCAL_DURABLE`, `RETRYABLE_REJECTION` or `TERMINAL_REJECTION`;
  - a retryable rejection blocks the barrier;
  - a terminal rejection advances it only under the reviewed `SKIP_AND_RECORD` policy, through a durable loss record;
    the default is `HALT`;
  - the accepted watermark counts a rejected event only when Control records a terminal disposition.
- **P0 — Pinned action revisions** (14.2): deliveries are bound at creation to an immutable action revision and target
  digest. `onRevisionChange` is `Supersede` (default) or `ExecuteOriginal`, and historical events are never run under
  a new definition.
- **P0 — Information-flow policy** (12.2, and applied in 13.1, 14 and 15.4): classification labels on event and tool
  schemas, principal clearance, a restricted CEL environment and redacted activation before evaluation, label
  propagation, sink ceilings on tool inputs, implicit-flow hiding, and no declassification.
- **P1:**
  - three source classes: `EVENT_LOG`, `STATE_SNAPSHOT`, `NON_REPLAYABLE` (6.5);
  - deterministic Quartz catch-up identity (latest missed occurrence, computed by ToolGate) and lock-ordering against
    concurrent changes (11.2, 11.3);
  - canonical sequence namespaces `emitSeq`, `outboxSeq`, `leaseEpoch` and `checkpointId` (6.2.1), and the stale
    `EMIT_ACK DURABLE` wording fixed (6.3).

**Changes in revision 5** (design review findings)

- **P0 — Action guarantees** (14.3, 14.4, 18): one logical action record per event is separated from external
  effects. Effects are at-least-once with reconciliation, and an ambiguous outcome is `OUTCOME_UNKNOWN`, reconciled
  before any retry. Exactly-once needs target idempotency or fencing (#230).
- **P0 — Schedules** (11.1, 11.2): a configuration outbox, a schedule reconciliation controller with periodic full
  repair, `observedGeneration`, and a fire-time guard that checks the canonical assignment generation and state.
- **P0 — Composite credentials** (15.2): they come only from the approved binding. Each is bound to the version
  digest, binding revision, child identity and allowed principals, and re-checked on every step.
- **P0 — Resource authorization in composites** (15.4.1): child arguments are untrusted. The child tool authorizes
  them against its approved scope (traversal, symlink and tenant tests), not CEL.
- **P0 — Checkpoints** (6.6, 5.3): a contiguous checkpoint barrier, with separate `LOCAL` (outbox) and `ACCEPTED`
  (Control) levels. The IMAP example is fixed.
- **P1:**
  - dispatch-time re-authorization for pending actions (14.2);
  - milestones renumbered in dependency order (21);
  - leases separated from execution authority (7);
  - source replay classes (6.5);
  - retention, cursor and replay semantics (12.1);
  - redaction before matching (13.1);
  - tickets treated as credentials (13.2);
  - explicit CEL limits (15.2);
  - global and credential rotation during runs (15.2).
- **P2:** the orchestration workers are designed to move out of Control later (15.4).

**Changes in revision 4**

- **Schedule triggers** (section 11): Quartz in Control publishes a trigger event on a cron or interval schedule, with
  CronJob-style concurrency and misfire rules.
- **Event router and socket subscriptions** (sections 12–13): every trigger event goes through a transactional outbox
  to authorized WebSocket subscribers.
- **Actions** (section 14): device, server and schedule events can start an authorized MCP tool or composite
  execution. It runs as a registered Agent, through the normal policy, ASK and permit path, with one logical action record per event (revision 5 refines the effect guarantee).
- **Composite MCP** (section 15): a JSON/YAML-defined tool that runs zero or more tools sequentially, with typed CEL
  expressions, globals, credential slots and defaults. It is executed durably by Control, and each child call is
  authorized separately.
- **Identity-bound events** (section 6.4): Control attaches the verified producer identity to every event. Actions
  can rely on that identity, never on `data`.
- **Dependency noted:** the IMAP IDLE push sample needs Tool Host TCP tunnels, which ADR 013 decided but the Tool Host
  does not implement yet (its README lists them as not built).

**Changes in revision 3**

- Two implementation modes:
  - **POLL:** the SDK calls `check()` on a fixed interval.
  - **PUSH:** the author owns a native listener and emits through an async SDK emitter.
- Optional periodic resync for push sources that can drop events.
- Events use the CloudEvents 1.0 envelope. A source-level event `id` gives effectively-once storage.
- Checkpoints commit only after their events are durable (offset-commit semantics).
- Bounded outbox with backpressure instead of silent drops.
- Liveness heartbeat, graceful-shutdown timeouts and full-jitter backoff.
- Lease timing follows leader-election practice: renew deadline before expiry, plus skew grace.
- Resources follow a spec/status model with `observedGeneration` and conditions.
- API conventions: RFC 9457 errors, ETag/`If-Match`, `Idempotency-Key`, JSON Merge Patch, `Retry-After`, dry run.
- Rolling package upgrades per assignment, with schema-compatibility rules.
- OpenTelemetry metrics and traces, a data classification for event fields, and fault-injection and soak tests.

## 0. Fresh-build rule (applies to every module)

Triggers have not been released, so nothing exists that needs to keep working. Every module follows these rules,
and they override any wording elsewhere in this plan:

- **No backward compatibility.** There is no support for earlier drafts of trigger contracts, wire frames, package
  descriptors, desired documents or stored state. Before release, a contract changes in place and its generated
  Java, Rust, PHP and TypeScript bindings are regenerated. There is no `v1` kept beside a `v2` of a trigger contract.
- **No database migrations.** Trigger tables, indexes, row-level security and the Quartz tables are added directly to
  the single fresh schema (`apps/control-plane/src/main/resources/db/migration/V1__initial_schema.sql`, its
  Quickstart twin `db/quickstart/V1.sql`, and `db/schema-ownership.json`). No new `V2__…` files, and no upgrade,
  backfill or conversion code. A store that doesn't match the current schema is refused at startup; it is not
  converted or deleted. Crash recovery within the current schema is still required.
- **No legacy or outdated code.** No compatibility constructors, aliases, deprecated fields, fallback paths,
  "old format" readers or switches that keep a previous behavior. Each behavior has **one production code path**.
  Code made obsolete by a later module is deleted in that module, not left behind.
- **Feature flags are rollout gates, not legacy paths.** The `toolgate.triggers.<module>` flags of section 24.1 hide
  unfinished work only. Each flag and its "off" branch are removed once the module's slice checkpoint passes, so the
  release has no trigger feature flags.
- **This is the same rule #230 follows** (see `AGENTS.md`, *Fresh breaking-build constraint*). Triggers build on the
  current #230 contracts and schemas, never on earlier versions of them.

**Not covered by this rule:** behavior that ToolGate offers to tenant-authored trigger packages at runtime. These are
product features between two versions of a customer's own package, not compatibility code in ToolGate:

- `eventType` major versions and the upload-time event-schema compatibility check (5.9);
- `migrateCheckpoint` between two package versions (T12);
- rolling package upgrades and `:rollback` per assignment (10.4).

## 1. What v1 delivers

1. **Authoring.** A developer writes a trigger with a dedicated annotation:
   - `@ServerTrigger` / `@server_trigger` for triggers that need network or credentials, such as a mail listener.
   - `@ClientTrigger` / `@client_trigger` for triggers that watch an endpoint device.

   Either can be implemented as **POLL** (the SDK calls `check()` every N seconds) or **PUSH** (the author's code
   registers a native listener and calls the SDK's async `emit()` when something happens). `toolgate pack` generates
   the trigger's full descriptor and the runnable artifact, in the same `.tgpkg` a tool package uses.
2. **Upload.** A tenant administrator uploads the package on a dedicated **Triggers** page. In v1 this page is raw: a
   file upload plus a JSON/YAML editor and a raw JSON response view.
3. **Allocation.** The administrator allocates a trigger to a Tool Host pool (server) or to devices and Device Groups
   (client). Control turns that into signed desired state. The executor installs the package, takes a lease, starts
   the trigger and keeps it running.
4. **Lifecycle management.** The administrator deploys, runs, pauses, stops and removes triggers per assignment,
   Device Group or pool, device, or instance.
5. **Reporting.** Over the existing check-in loop (the "ping-pong"), every executor sends a snapshot of the trigger
   instances it holds and the events since the last ack. The reply renews leases and acknowledges events.
6. **Querying.** The administrator queries status and event history through the API.
7. **Schedules.** An administrator defines a Quartz schedule (cron or interval, time zone, misfire policy) that
   publishes a trigger event with a typed payload, with no package code involved.
8. **Socket delivery.** Authorized users and agents subscribe over a WebSocket and receive matching events in real
   time, with resume after disconnect.
9. **Actions.** An assignment may declare actions: when an event matches a typed condition, Control starts an MCP
   tool or composite tool as the assignment's run-as Agent, under normal policy, ASK and permits. A duplicate event
   creates exactly one logical action record; effects are at-least-once with reconciliation (section 14.3).
10. **Composite MCP.** Administrators upload a JSON/YAML composite tool that calls zero or more existing tools, with
    typed expressions, globals, credential slots and defaults. It is published as one MCP tool that agents, actions and
    schedules can call.

All of it is tenant-scoped, under the boundaries in ADR 029.

**Not in v1:** action chaining (actions triggered by action outcomes), compensation (sagas) in composites, parallel
or looping composite steps, inbound webhooks, client network egress, Marketplace distribution, and a designed UI.
Section 22 lists these.

## 2. Standards and patterns adopted

| Area | Practice adopted | Where it applies here |
|---|---|---|
| Event envelope | **CloudEvents 1.0** (`id`, `source`, `type`, `subject`, `time`, `dataschema`, `data`), with W3C `traceparent` via the distributed-tracing extension | Every `FIRED` and `STATE` event (section 6.4) |
| Delivery semantics | At-least-once transport with consumer dedupe on `(source, id)`, which gives effectively-once storage; per-key ordering only | Executor → Control (section 6.5) |
| Polling vs push sources | Polling and instant/push triggers as two first-class kinds (as in Zapier, n8n, Logic Apps); periodic **resync** for watch-based sources (Kubernetes informer resync, inotify `IN_Q_OVERFLOW` recovery) | Section 5 |
| Source connectors | Task lifecycle `start / poll or push / stop`, with **offsets committed only after records are flushed** (Kafka Connect `SourceTask`, Debezium) | Checkpoints (section 6.6) |
| Backpressure | Bounded queue with high/low watermarks; producers wait instead of losing data; drop only by a declared, reviewed policy (Reactive Streams) | Section 6.7 |
| Health | Separate readiness (`TRIGGER_READY`) and liveness (heartbeat watchdog); graceful termination period, then kill (Kubernetes probes, `terminationGracePeriodSeconds`) | Section 6.8 |
| Retries | Exponential backoff with **full jitter** and a crash-loop budget | Section 6.9 |
| Leader election | Lease with `leaseDuration`, a shorter `renewDeadline` and a retry period (Kubernetes `Lease` / client-go leader election); **monotonic fencing token** (Kleppmann) | Section 7 |
| Resource model | Declarative `spec` / `status`, `metadata.generation`, `status.observedGeneration`, typed **conditions** with `reason` and `lastTransitionTime` (Kubernetes API conventions) | Sections 9 and 10 |
| HTTP API | RFC 9457 problem details, ETag + `If-Match` (RFC 9110), `Idempotency-Key` header (IETF draft), JSON Merge Patch (RFC 7386), opaque cursor pagination, `429` + `Retry-After`, server-side dry run | Section 10 |
| Rollouts | Rolling update with `maxUnavailable`, pausable, revision history and rollback (Kubernetes Deployments; ADR 014 lifecycle) | Section 10.4 |
| Schema evolution | Event schemas backward-compatible within a major version; breaking change means a new `type` (`….v2`) (schema-registry BACKWARD mode) | Section 5.6 |
| Observability | OpenTelemetry metrics and traces with semantic names; ToolGate's correlation IDs (ADR 031) | Section 20 |
| Supply chain and least privilege | Signed, digest-pinned packages; capability declarations reviewed as a security delta; brokered credentials (existing ADR 013/014) | Sections 5.5 and 19 |
| Scheduling | **Quartz** (`quarkus-quartz`) with a clustered JDBC job store on PostgreSQL; **Kubernetes CronJob** semantics for `concurrencyPolicy`, `startingDeadlineSeconds` and suspend; time-zone-aware cron | Section 11 |
| Reliable publish | **Transactional outbox** (event and outbox row in one transaction; workers claim with `FOR UPDATE SKIP LOCKED`); dead-letter after bounded retries | Section 12 |
| Realtime delivery | WebSocket (RFC 6455) with a versioned subprotocol; short-lived single-use connection ticket; credit-based flow control; resumable cursors; protocol documented in **AsyncAPI 3.0** | Section 13 |
| Typed expressions | **CEL (Common Expression Language)**: non-Turing-complete, type-checked against JSON Schemas at publish time, with cost limits (as in Kubernetes admission policies and Envoy) | Sections 14 and 15 |
| Automation identity | A dedicated non-human principal (the existing **Agent** with a `SERVICE` profile) with least-privilege grants and an explicit delegation chain (`act`), never the event producer's identity | Section 14 |
| Workflow execution | **Durable execution**: each step persisted in the ledger as a child invocation, resumed after a crash, idempotent by business key | Section 15 |

## 3. What already exists and is reused

| Need | Existing mechanism (on `main`) | Use in v1 |
|---|---|---|
| Annotation → descriptor | Java `DescriptorProcessor`, `@ToolPackage`, `@ServerTool`, `@ClientTool`, `@Param`, `@Example`, `@HttpRoute`, `@TcpDestination` (`tcp().connect`), `@Secret`, `@Global`, `@DeviceFileAccess`; Python `@tool_package`, `@server_tool`, `@client_tool` | New trigger annotations; every capability annotation reused as-is |
| Package and digests | `.tgpkg`, `PackageManifest` v2, `descriptorSetDigest`, `artifactDigest`, `packageDigest` (ADR 014); `toolgate check`, `toolgate pack` | Triggers are a new export kind |
| Upload, review, enable, security delta, version lifecycle | ADR 014, `POST /api/control/v1/packages/uploads` | Unchanged |
| Server placement and config | Tool Hosts enroll as devices, sit in pools, fetch assignments; `DeploymentBinding` (ADR 013, ADR 020) | A server assignment produces a binding revision |
| Server egress and secrets | Broker `ROUTE_CALL`, CONNECT/TCP tunnel, `SECRET_REDEEM`, `access/secrets/deliver`, fencing-token-bound capability (ADR 013, ADR 027) | Bound to a trigger lease instead of a permit |
| Client delivery and sandbox | Control-built OCI image (ADR 026), signed fleet documents (ADR 010), device files area and `FILES_*` broker (ADR 027) | Unchanged; adds `FILES_WATCH` |
| Ping-pong | `POST /api/control/v1/endpoint/check-in`, used by endpoint clients and enrolled Tool Hosts | One `triggers` block for both |
| Sequence, ack, dedupe, heartbeat | `RunEventBatch`, `RunEventAck`, `RunEventGap`, `LeaseHeartbeat` | Same semantics |
| Tenancy, kill switch, correlation | ADR 029, ADR 017, ADR 031 | Applied throughout |
| Renewable execution leases, revocation and watchdog | ADR 032 (#230 S05) | Trigger leases follow the same lease, heartbeat and epoch model; no second lease implementation |
| Operations, attempts and business keys | ADR 034, ADR 035, S08 business-key policy, S10 operation finalizer (#230) | Actions record operations here; no trigger ledger (14.3) |
| Effect identity, grants, consent, resend and reconciliation | ADR 036, ADR 053–056 (#230 S15–S20) | Every action effect uses this path (14.3, 14.4) |
| Target fencing | ADR 057 (#230 S21): `SHARED_SQL` and `SQL_WITH_EXTERNAL_ACTION` fenced target classes | The only basis for "at most one effect" claims on action targets |
| Worker and broker containment | ADR 033 and the S11–S14 qualified Python OCI tier (#230) | Server trigger sandboxes use the same tier and claims (6.10) |
| Errors and contracts | problem+json responses in Control; generated contracts; proposed vs served OpenAPI | Same conventions |

## 4. Concepts and identifiers

| Term | Meaning | Assigned by |
|---|---|---|
| **Trigger definition** | One trigger export in a package version, ID `<packageId>/<name>`, pinned by `packageDigest` + `triggerDescriptorDigest` | SDK processor; verified on upload |
| **Mode** | `POLL` or `PUSH` (section 5) | Author |
| **Executor** | `SERVER` (Tool Host) or `CLIENT` (endpoint device). Both are enrolled devices of Control | Assignment target |
| **Trigger assignment** | Declarative resource: "run definition X with config C on target T" | Control (`metadata.generation` increments on every spec change) |
| **Trigger instance** | The unit that runs. **Server:** one per assignment, on one host of the pool. **Client:** one per targeted device | Control |
| **Override** | A `RUN`, `PAUSE` or `STOP` setting at group, device or instance scope (section 10.3) | Admin action |
| **Lease** | Control's time-bounded grant for one executor to run one instance at one config revision. Carries a monotonic `leaseEpoch` (the fencing number) and a random `leaseToken` (the bearer secret) | Control |
| **Event** | A CloudEvent. `source` identifies the instance, `id` is unique per source | `id`: author or executor; everything else: executor and Control |
| **Checkpoint** | Opaque trigger state (≤ 16 KiB) committed after the events emitted before it are durable | Author via the SDK |

The tenant is never a field anyone sends. Control derives it from the authenticated certificate or admin session.

## 5. Authoring

### 5.1 The two modes

| | **POLL** | **PUSH** |
|---|---|---|
| Use when | The source can only be queried: REST APIs without push, mailbox search, a status endpoint | The source or a native library notifies you: file-system watch, IMAP IDLE, a message broker consumer, a WebSocket or streaming API |
| Who owns the loop | The SDK runtime, on `intervalMs` | The author's listener (native library threads or callbacks, or an async event loop) |
| Author implements | `check(config, ctx) → List<Event>` | `start(config, ctx, emitter)`, `stop()`, optionally `reconcile(config, ctx, emitter)`, `onPause()`, `onResume()` |
| SDK provides | Scheduling, cancellation, checkpoint, event emission | An **async emitter** (`emit`, `checkpoint`, `reportHealth`), lifecycle, heartbeats, the resync scheduler, and capability-scoped network and file access |
| Missed-event recovery | Inherent (the next check sees the state) | `reconcile()` on start, after failover, and every `resyncIntervalMs` |
| Latency | Up to `intervalMs` | Near-real-time |

The author is responsible for the listener: choosing the library, subscribing, reconnecting, mapping native
notifications to events and choosing a stable event `id`. The SDK is responsible for everything between `emit()` and
Control: validation, durability, ordering, dedupe, rate limits, checkpoint commit, health and shutdown.

### 5.2 POLL example: mail via a REST API (server)

```java
@ToolPackage(id = "examples.mail-watch", version = "1.0.0" /* other fields per @ToolPackage */)
@HttpRoute(id = "graph-messages", method = HttpMethod.GET /* host + path template per @HttpRoute */)
@Secret(name = "mailbox-oauth" /* brokered: injected by the broker, never seen by the trigger */)
public final class MailWatch {

    public record Config(@Param(description = "Mailbox to watch") String mailbox,
                         @Param(description = "Subject filter") String subjectContains) {}

    /** Metadata only. No body, no attachments. */
    public record NewMessage(String messageId, String from, String subject, String receivedAt) {}

    @ServerTrigger(name = "new-message", mode = TriggerMode.POLL,
                   description = "Reports new messages in a mailbox (metadata only)",
                   config = Config.class, event = NewMessage.class,
                   eventType = "examples.mail.message.received.v1",
                   intervalMs = 60_000, maxEventsPerHour = 600)
    @Example(/* config + stubbed route responses + expected events */)
    public PollResult<NewMessage> check(Config config, PollContext ctx) {
        String since = ctx.checkpoint().map(c -> c.getString("since")).orElse(ctx.startedAt());
        var page = ctx.routes().call("graph-messages", Map.of("mailbox", config.mailbox(), "since", since));
        var events = parse(page).stream()
            .filter(m -> m.subject().contains(config.subjectContains()))
            .map(m -> TriggerEvent.of(m).id(m.messageId()).subject(config.mailbox()))   // stable source-level id
            .toList();
        return PollResult.of(events).checkpoint(Checkpoint.of("since", latest(page, since)));
    }
}
```

### 5.3 PUSH example: IMAP IDLE with a native mail library (server)

```java
@ToolPackage(id = "examples.mail-idle", version = "1.0.0")
@TcpDestination(id = "imap", hostSetting = "imapHost", port = 993)
@Secret(name = "imap-password" /* CREDENTIALS_IN_SANDBOX: the TLS session is end-to-end, so the trigger logs in */)
public final class MailIdle {

    @ServerTrigger(name = "new-message", mode = TriggerMode.PUSH,
                   description = "Reports new messages as the IMAP server announces them",
                   config = Config.class, event = NewMessage.class,
                   eventType = "examples.mail.message.received.v1",
                   resyncIntervalMs = 900_000,          // reconcile() every 15 min and after any restart
                   maxEventsPerHour = 600, overflow = OverflowPolicy.BLOCK)
    public static final class Listener implements PushTrigger<Config, NewMessage> {
        private volatile IMAPFolder inbox;

        @Override
        public void start(Config config, PushContext ctx, TriggerEmitter<NewMessage> emitter) throws Exception {
            // The native library connects through the declared destination only; ctx supplies the socket factory.
            var props = new Properties();
            props.put("mail.imaps.socketFactory", ctx.tcp().socketFactory("imap"));
            var store = Session.getInstance(props).getStore("imaps");
            store.connect(config.user(), ctx.secrets().redeem("imap-password"));
            inbox = (IMAPFolder) store.getFolder(config.mailbox());
            inbox.open(Folder.READ_ONLY);
            // The push notification is only a HINT. No ordering or completeness of library callbacks is assumed.
            // It wakes reconcile(), which reads the authoritative, explicitly sorted UID range.
            inbox.addMessageCountListener(new MessageCountAdapter() {
                @Override public void messagesAdded(MessageCountEvent e) { ctx.requestReconcile(); }   // coalesced, single-flight
            });
            ctx.runInBackground(() -> { while (!ctx.stopping()) inbox.idle(); });   // SDK-managed thread, cancelled on stop
            ctx.requestReconcile();                                                  // catch up from the checkpoint first
            emitter.ready();
        }

        @Override
        public void reconcile(Config config, PushContext ctx, TriggerEmitter<NewMessage> emitter) throws Exception {
            long validity = inbox.getUIDValidity();
            var cp = ctx.checkpoint();                                   // {uidValidity, lastUid} or empty
            long from = cp.filter(c -> c.getLong("uidValidity") == validity)
                          .map(c -> c.getLong("lastUid") + 1)
                          .orElse(1L);                                   // UIDVALIDITY changed or no checkpoint: rescan
            Message[] msgs = inbox.getMessagesByUID(from, UIDFolder.LASTUID);
            Arrays.sort(msgs, Comparator.comparingLong(m -> uid(m)));    // explicit order; never trust callback order
            for (var m : msgs) {
                long uid = uid(m);
                var event = toEvent(m);                                  // metadata only
                // Durable source identity: account + mailbox + UIDVALIDITY + UID. A UID alone can be reused after
                // a UIDVALIDITY change. The RFC 5322 Message-ID is carried in data for consumers, not used as the id.
                emitter.emit(TriggerEvent.of(event)
                    .id("imap:" + config.accountRef() + ":" + config.mailbox() + ":" + validity + ":" + uid)
                    .position(Checkpoint.of("uidValidity", validity, "lastUid", uid)));   // monotonic within a validity
            }
        }

        @Override
        public void stop() throws Exception { if (inbox != null) inbox.getStore().close(); }
    }
}
```

### 5.4 PUSH example: directory monitoring (client)

Client triggers run inside the device sandbox, which sees only the device files area (ADR 027). Native file-system
notifications reach the trigger in one of two ways:

**A. Executor-native watch (default, portable).** The author subscribes through the SDK. The executor uses the host's
native API (inotify, `ReadDirectoryChangesW`, FSEvents), applies the files-area confinement rules, and delivers
notifications as broker events. This works the same on Linux, Windows and macOS, including when the sandbox engine
runs in a VM.

```java
@ToolPackage(id = "examples.inbox-watch", version = "1.0.0")
public final class InboxWatch {
    @ClientTrigger(name = "new-file", mode = TriggerMode.PUSH, sourceReplay = SourceReplay.STATE_SNAPSHOT,
                   description = "Reports each file version present in a folder that has not been reported before",
                   config = Config.class, event = FileObserved.class,
                   eventType = "examples.files.observed.v1",
                   resyncIntervalMs = 300_000, maxEventsPerHour = 600)
    @DeviceFileAccess                                   // read-only, recorded as DEVICE_FILES_READ
    public static final class Listener implements PushTrigger<Config, FileObserved> {
        private FileWatch watch;

        @Override
        public void start(Config config, PushContext ctx, TriggerEmitter<FileObserved> emitter) {
            // OS file events are coalesced, reordered and dropped (inotify overflow, ReadDirectoryChangesW buffer
            // overrun, FSEvents coalescing). They are treated as hints that wake reconcile(), never as the event itself.
            watch = ctx.files().watch(config.folder(), WatchKind.ANY, change -> ctx.requestReconcile());
            ctx.requestReconcile();
            emitter.ready();
        }

        @Override
        public void reconcile(Config config, PushContext ctx, TriggerEmitter<FileObserved> emitter) {
            // Exact version keys, no hashes. Capacity equals the files area's maxEntries (≤ 1 024, ADR 027), so the set
            // can always hold every present file. Overflow is still handled explicitly (section 5.6, SnapshotState).
            var reported = ctx.checkpoint().map(SnapshotState::from).orElseGet(SnapshotState::empty);
            var present = ctx.files().list(config.folder()).stream()     // a complete listing, or it throws
                .filter(f -> f.name().endsWith(config.extension()))
                .sorted(Comparator.comparing(FileInfo::versionKey))      // deterministic order
                .toList();
            var next = reported.retainOnly(present);                     // prune keys of files that are gone
            for (var f : present) {
                // Stable version identity, computed by the executor (FILES_INFO): file ID (inode or NTFS file ID),
                // size and modification time in ns. Not the name: a rename keeps the version and is not re-reported.
                String key = f.versionKey();
                if (next.contains(key)) continue;
                next = next.add(key);                                    // throws SnapshotCapacityExceeded if full
                emitter.emit(TriggerEvent.of(new FileObserved(f.name(), f.size(), f.modifiedAt()))
                                         .id("file:" + config.folder() + ":" + key)
                                         .position(next.toCheckpoint()));   // snapshot position, not history
            }
        }

        @Override public void stop() { if (watch != null) watch.close(); }
    }
}
```

**B. Native library inside the sandbox (opt-in, reviewed).** With `@DeviceFileAccess(nativeMount = true)`, the
executor bind-mounts the files area **read-only** at `/toolgate/files`, so a native watcher such as Java
`WatchService` or Python `watchdog` works directly. It is available only on executors that advertise
`files.nativeEvents`, which is Linux hosts with a native engine; elsewhere the instance is `UNSUPPORTED`.

This option is reviewed as a security delta (`DEVICE_FILES_MOUNT`). It is not the default, because notifications
across a VM boundary (Windows/macOS engines) are unreliable and the mount widens what the sandbox can see.

### 5.5 Python

```python
@server_trigger(name="new-message", mode="poll", config=Config, event=NewMessage,
                event_type="examples.mail.message.received.v1", interval_ms=60_000)
def check(config: Config, ctx: PollContext) -> PollResult[NewMessage]: ...

@client_trigger(name="new-file", mode="push", source_replay="STATE_SNAPSHOT", config=Config, event=FileObserved,
                event_type="examples.files.observed.v1", resync_interval_ms=300_000)
@device_file_access()
class InboxWatch(PushTrigger[Config, FileObserved]):
    async def start(self, config: Config, ctx: PushContext, emitter: TriggerEmitter[FileObserved]) -> None:
        # Native notifications are coalesced, reordered and dropped: they are only HINTS that wake reconcile().
        self._watch = await ctx.files.watch(config.folder, kinds={"any"},
                                            on_change=lambda _change: ctx.request_reconcile())
        ctx.request_reconcile()                       # catch up from the checkpoint first
        await emitter.ready()

    async def reconcile(self, config: Config, ctx: PushContext, emitter: TriggerEmitter[FileObserved]) -> None:
        # Exact 32-byte version keys (file ID, size, mtime ns), with capacity = the files area's max_entries.
        reported = SnapshotState.from_checkpoint(ctx.checkpoint) or SnapshotState.empty()
        listing = await ctx.files.list(config.folder)  # complete, or raises: a partial listing never prunes
        present = sorted((f for f in listing if f.name.endswith(config.extension)), key=lambda f: f.version_key)
        state = reported.retain_only(present)          # prune only items confirmed absent
        for f in present:
            key = f.version_key                        # not the name: a rename is not re-reported
            if key in state:
                continue
            state = state.add(key)                     # raises SnapshotCapacityExceeded, never truncates
            await emitter.emit(
                TriggerEvent(FileObserved(name=f.name, size=f.size, modified_at=f.modified_at))
                .id(f"file:{config.folder}:{key.hex()}")
                .position(state.to_checkpoint()))      # snapshot position, not history

    async def stop(self) -> None:
        await self._watch.close()
```

In Python, `await emitter.emit(event, id=...)` returns when the event is durable. A Java `emit()` returns a
`CompletionStage<EmitResult>`. Neither blocks the author's listener thread unless backpressure applies (section 6.7).

### 5.6 SDK API surface

| Type | Members |
|---|---|
| `PollContext` | `config()`, `globals()`, `checkpoint()`, `startedAt()`, `routes()` (server), `tcp()` (server), `secrets()` (server, `CREDENTIALS_IN_SANDBOX` only), `files()` (client, read-only), `log()` |
| `PushContext` | Everything in `PollContext`, plus `tcp().socketFactory(id)` / `tcp().connect(id)` for native libraries, `files().watch(...)`, `files().list(...)` returning `FileInfo` with an executor-computed `versionKey()`, `requestReconcile()` (coalesced, single-flight), `runInBackground(Runnable)` (SDK-owned, cancelled on stop), `stopping()` |
| `SnapshotState` | Exact set of reported version keys (no hashing, so no collisions) for `STATE_SNAPSHOT` sources, stored in the checkpoint. Its capacity is declared, and `STATE_SNAPSHOT` checkpoints may be up to 128 KiB. Contract below |

**`SnapshotState` contract (v1 reference implementation):**

- **It is a snapshot, not a history.** An event means "this version was present at reconcile time and had not been
  reported", never "this file was created". Events are never presented as a complete change history, in the SDK
  documentation, the event schema description or the console.
- **Exact keys.** Version keys are stored exactly, so there are no hash collisions. `retainOnly(present)` removes keys
  only for items **confirmed absent** by a complete listing.
  - If a listing fails or is partial (I/O error, permission error, truncated page), nothing is pruned and the reconcile
    is retried. A partial listing never counts as "absent".
- **Capacity.** It must be declared, and must be at least the source's maximum item count when one exists. For the
  device files area that is `maxEntries` (≤ 1 024), so the capacity cannot be exceeded.
- **The encoded size is bounded, not just the count.** Keys have a declared `maxKeyBytes`. File version keys use a
  fixed 32-byte binary encoding: 16-byte file ID (inode or NTFS 128-bit ID), 8-byte size and 8-byte mtime in ns. So
  1 024 keys are 32 KiB raw, about 43 KiB base64-encoded, plus a header under 1 KiB, which is well within the 128 KiB
  limit.
  - The processor rejects any declaration where `capacity × maxKeyBytes × 4/3 + 1 KiB > maxCheckpointBytes`.
  - At runtime, the SDK checks the actual encoded size before committing. Exceeding it is treated as
    `SnapshotCapacityExceeded`, never as a truncated checkpoint.
- **Overflow** (sources without a hard item bound) is never silent:
  1. `add()` throws `SnapshotCapacityExceeded`, and the instance reports `Degraded (SnapshotCapacityExceeded)`. The
     checkpoint is **not** advanced.
  2. The trigger switches to a **durable rescan mode**. Each reconcile emits every present item, and storage dedupes
     them by `id` within retention. Beyond retention, the operation ledger dedupes any action (section 14.3). The
     console states that duplicate notifications to subscribers are possible in this mode.
  3. It leaves rescan mode only when the item count falls below 90% of capacity, or capacity is raised by a new package
     version.
- **Loss of state.** If the checkpoint is lost, or migrated by discarding (T12), every present item is reported again.
  Storage and the ledger dedupe by `id` as above.
| `TriggerEmitter<E>` | `emit(TriggerEvent<E>) → CompletionStage<EmitResult>` (completes at `LOCAL_DURABLE` or `TERMINAL_REJECTION`; a `RETRYABLE_REJECTION` is retried by the SDK and keeps the future pending, section 6.6); `acceptedUpTo() → Flow.Publisher<Position>` (optional, positions Control has accepted); `checkpoint(Checkpoint)` (barrier, section 6.6); `ready()`; `reportHealth(HEALTHY \| DEGRADED \| UNHEALTHY, reason)` |
| `TriggerEvent<E>` | `of(data)`, `id(String)` (stable source-level identity; an executor UUIDv7 if omitted), `position(Checkpoint)` (the source position *after* this event; drives the checkpoint barrier), `subject(String)`, `time(Instant)` |

**Source identity rules** (enforced by review guidance and samples; the processor warns where it can):

- An event `id` must be stable across restarts, reconnects and reconciles, and must include every component the source
  needs for uniqueness. Examples:
  - IMAP: account, mailbox, `UIDVALIDITY` and UID;
  - an event log: partition and offset;
  - a file version: path plus file ID, size and modification time.
  A bare UID, a library object reference or a wall-clock time alone is never enough.
- Positions must be monotonic in `emitSeq` order within one source epoch, such as one `UIDVALIDITY`. The SDK compares
  each new position with the last one under the position's declared order. A regression is a programming error:
  `NonMonotonicPosition`, handled as `HALT`. A source-epoch change (a new `UIDVALIDITY`) starts a new order, and is
  reported as a `STATE` event.
- Native callbacks are hints. Samples never assume a library's callback order or completeness. They use
  `requestReconcile()` and read the authoritative state in an explicit order.
| `PushTrigger<C, E>` | `start`, `stop`, default `reconcile`, `onPause`, `onResume` (defaults: pause = `stop`, resume = `start` from the last checkpoint) |
| Test support | `ToolHarness.trigger(...)`: drive N polls, inject watch events, inject network faults, assert emitted events and checkpoints |

### 5.7 Executor eligibility

This follows the existing rule for tools (ADR 026 §4).

| Declared as | Tool Host | Client |
|---|---|---|
| `@ServerTrigger` using routes, TCP destinations or secrets | Yes | No (clients have no egress or secrets, ADR 013 §7): `UNSUPPORTED` |
| `@ServerTrigger` using none of them | Yes | Yes |
| `@ClientTrigger` | No | Yes |

### 5.8 Rules the processor enforces (compile errors)

- A POLL trigger is an annotated method returning `PollResult<E>` (Python: a decorated function). A PUSH trigger is an
  annotated class implementing `PushTrigger<C, E>` (Python: a decorated class with async `start` and `stop`).
- Config, event and checkpoint types must be records or dataclasses the descriptor generator supports. Event types
  must fit 16 KiB serialized, config 8 KiB and checkpoints 16 KiB (128 KiB for `STATE_SNAPSHOT` sources using `SnapshotState`).
- `eventType` is required, reverse-DNS, and ends with a major version (`.v1`).
- POLL `intervalMs` ≥ 5 000. PUSH `resyncIntervalMs` is 0 or ≥ 60 000, and the processor warns when it is 0 for a
  file-watch or network listener. `maxEventsPerHour` ≤ 3 600.
- Capabilities: `@ServerTrigger` may reference the package's routes, TCP destinations, secrets and globals.
  `@ClientTrigger` may use only `@DeviceFileAccess`, which is read-only in v1; `nativeMount` is a separate permission.
- `overflow` is `BLOCK` by default. `DROP_OLDEST` and `DROP_NEWEST` are allowed only for event types marked
  `lossTolerant = true`, and are shown in the security delta.
- Trigger names share the package export namespace with tools.

### 5.9 Generated descriptor

```json
{
  "kind": "SERVER_TRIGGER",
  "triggerId": "examples.mail-idle/new-message",
  "mode": "PUSH",
  "executors": ["SERVER"],
  "eventType": "examples.mail.message.received.v1",
  "configSchema": { "...": "JSON Schema 2020-12" },
  "eventSchema": { "...": "JSON Schema 2020-12, fields may carry x-toolgate-classification" },
  "checkpointSchemaVersion": 1,
  "schedule": { "resyncIntervalMs": 900000 },
  "limits": { "maxEventsPerHour": 600, "maxEventBytes": 16384, "maxCheckpointBytes": 16384, "memoryMiB": 256,
              "maxBrokerCallsPerMinute": 120, "stopGraceMs": 10000 },
  "overflow": "BLOCK",
  "routes": [], "tcpDestinations": ["imap"], "secrets": ["imap-password"],
  "permissions": ["CREDENTIALS_IN_SANDBOX"],
  "tiers": ["OCI_SANDBOX"],
  "selfTests": ["…from @Example…"]
}
```

`triggerDescriptorDigest` (RFC 8785) is part of `descriptorSetDigest`, so the existing security delta and re-approval
rules cover triggers.

**Schema evolution on upgrade:**

- Event schemas must stay backward-compatible within an `eventType` major version. Removing or retyping a field, or
  adding a required field, needs a new `….vN`. Control checks this on upload against the previous enabled version.
- A config schema change is checked against every existing assignment in a dry run before enable.
- A changed `checkpointSchemaVersion` makes the executor start from no checkpoint, which triggers a full
  `reconcile()`, unless the trigger implements `migrateCheckpoint`.

## 6. Runtime

### 6.1 Process model

Both executors start the trigger in the same image and sandbox tier as a tool, with `TOOLGATE_PROTOCOL=2`,
`TOOLGATE_EXECUTION_TARGET=SERVER|CLIENT` and `TOOLGATE_EXECUTION_MODE=TRIGGER`. One process runs one instance.

The supervisor logic is one shared Rust crate (`crates/trigger-supervisor`), linked by both the Tool Host and the
endpoint client.

### 6.2 Protocol frames (a trigger mode of tool protocol v2)

| Direction | Frame | Purpose |
|---|---|---|
| stdin | `TRIGGER_START {triggerId, instanceId, configRevision, leaseEpoch, config, globals, secretHandles, checkpoint}` | First line, exactly once |
| stdin | `EMIT_ACK {emitSeq, status: LOCAL_DURABLE \| RETRYABLE_REJECTION \| TERMINAL_REJECTION, reason?, outboxSeq?}` | Completes or fails the author's `emit()` future (section 6.6) |
| stdin | `ACCEPTED_UPTO {emitSeq}` | Every emit of **this process** up to `emitSeq` is resolved at Control (accepted, or terminal disposition recorded) |
| stdin | `CHECKPOINT_ACK {checkpointId, level: LOCAL \| ACCEPTED}` | Barrier advanced to that checkpoint at that level |
| stdin | `RECONCILE` | Run `reconcile()` (resync timer, start, failover) |
| stdin | `PAUSE` / `RESUME` | Lifecycle (section 10.3) |
| stdin | `BROKER_RESPONSE`, `BROKER_EVENT {subscriptionId, event}` | Broker replies; `FILES_WATCH` notifications |
| stdin | `STOP {reason, graceMs}` | Graceful stop |
| stdout | `READY` | `start()` completed (push) or config validated (poll) |
| stdout | `EMIT {emitSeq, event, position?}` | One event (section 6.2.1 for sequence namespaces) |
| stdout | `CHECKPOINT {checkpointId, afterEmitSeq, state}` | Explicit checkpoint. Subject to the same barrier as `position` |
| stdout | `HEALTH {status, reason}` | From `reportHealth` |
| stdout | `HEARTBEAT` | Sent every 10 s by the SDK runtime |
| stdout | `LOG`, `BROKER_REQUEST` | Unchanged frames. Broker operations: server `ROUTE_CALL`, `TCP_CONNECT` (existing tunnel), `SECRET_REDEEM`; client `FILES_LIST`, `FILES_READ`, `FILES_INFO`, `FILES_WATCH`, `FILES_UNWATCH` |
| stdout | `EXIT {code}` | Last frame on a clean stop |

POLL triggers use the same frames. The SDK runtime turns each `check()` result into `EMIT` frames followed by one
`CHECKPOINT`.

#### 6.2.1 Sequence namespaces (canonical)

Every sequence number in this plan belongs to exactly one of these namespaces. Frames and messages name the namespace
in the field name, never a bare `seq`.

| Name | Scope and lifetime | Assigned by | Appears in |
|---|---|---|---|
| `emitSeq` | One trigger **process**. Starts at 1 on every process start, increments in `emit()` call order. Never persisted, never sent to Control | SDK runtime | `EMIT`, `EMIT_ACK`, `ACCEPTED_UPTO`, `CHECKPOINT.afterEmitSeq` (stdio only) |
| `outboxSeq` | One `(instanceId, leaseEpoch)`. Starts at 1 for each new lease epoch, survives process restarts within the epoch, strictly increasing, no gaps. Persisted with the outbox record | Executor supervisor, when the record is fsynced | CloudEvent `tgsequence`; `TriggerSync.events`; `TriggerSyncAck.acked`; persisted checkpoints (`afterOutboxSeq`) |
| `leaseEpoch` | One instance, forever. Monotonic | Control | Leases, CloudEvent `tgleaseepoch`, every `outboxSeq` key |
| `checkpointId` | One process (stdio) | SDK runtime | `CHECKPOINT`, `CHECKPOINT_ACK` |
| check-in `sequence` | One executor's check-in stream (existing `EndpointCheckIn.sequence`) | Executor | Check-in only; unrelated to events |

Mapping rules:

- The supervisor keeps `emitSeq → outboxSeq` in memory for the life of the process. It translates `TriggerSyncAck`
  (in `outboxSeq`) into `ACCEPTED_UPTO` (in `emitSeq`) for the SDK.
- Records re-sent after a process restart have an `outboxSeq` but no `emitSeq`. They are tracked only by the
  supervisor.
- A **persisted** checkpoint is always `{leaseEpoch, afterOutboxSeq, state, level}`, never an `emitSeq`, so it stays
  meaningful after a restart.
- A terminal-rejection loss record (section 6.6) takes the next `outboxSeq` like any other record, so outbox sequences
  stay gap-free.

### 6.3 Supervisor responsibilities

- Runs an instance only with an unexpired lease for that config revision (section 7). It binds the broker capability,
  TCP tunnels, secret redemption and `FILES_WATCH` subscriptions to the lease, and tears them down when the lease ends.
- Validates each event against the signed `eventSchema`, enforces size and rate limits, and wraps the event in the
  CloudEvents envelope. The author never sets `source`, `type`, `dataschema` or the extension attributes.
- Writes the event to the outbox, assigns its `outboxSeq`, fsyncs, then sends `EMIT_ACK LOCAL_DURABLE`, or a
  rejection with its class (section 6.6).
- Applies backpressure (section 6.7), health (section 6.8), restarts (section 6.9) and the resync timer.

### 6.4 Event envelope (CloudEvents 1.0, JSON format)

```json
{
  "specversion": "1.0",
  "id": "AAMkAGI2…",
  "source": "toolgate:tenant:3f1c…:trigger-instance:c7b2…",
  "type": "examples.mail.message.received.v1",
  "subject": "ap@example.com",
  "time": "2026-10-31T10:29:58Z",
  "datacontenttype": "application/json",
  "dataschema": "toolgate:package:sha256:…:trigger:new-message:event",
  "data": { "messageId": "AAMkAGI2…", "from": "billing@vendor.example", "subject": "Invoice 1031", "receivedAt": "2026-10-31T10:29:58Z" },
  "tgleaseepoch": 7,
  "tgsequence": 41,
  "tgkind": "fired",
  "traceparent": "00-…-…-01"
}
```

- `STATE` changes use the same envelope with `type: io.ololabs.toolgate.trigger.state.v1` and `tgkind: state`.
- **Identity binding.** On ingest, Control attaches a verified `producer` record taken only from the mTLS certificate,
  the lease and the signed package, never from the payload:

  ```json
  "producer": { "kind": "DEVICE", "executorId": "device-42", "deviceGroups": ["finance-desktops"],
                "assignment": "finance-inbox-pdf", "instanceId": "8d0e…", "leaseEpoch": 4,
                "packageDigest": "sha256:…", "triggerDescriptorDigest": "sha256:…" }
  ```

  The record is delivered to subscribers as the extension attributes `tgproducerkind`, `tgproducerid` and
  `tgpackagedigest`, plus the `producer` object in ToolGate's stored view. Action conditions and argument mappings
  (section 14) can read `event.producer.*` as trusted and `event.data.*` as untrusted. Schedule events have
  `producer.kind: SCHEDULE`.
- `time` is the source's time (or the executor's clock when unknown). Ordering uses `(tgleaseepoch, tgsequence)`,
  never `time`.
- Control adds a server-side `receivedAt` and the verified executor identity when it stores the event. These are not
  part of what the executor sends.

### 6.5 Delivery guarantees

- **Executor → Control:** at-least-once. Unacknowledged events are retried indefinitely within the outbox bounds.
- **Storage in Control:** effectively-once. A unique key on `(tenant, source, id)` holds for the retention window, so
  duplicates are dropped. These come from retries, `reconcile()`, resync after a dropped watch notification, and server
  failover replaying from the last checkpoint.
- **Ordering:** guaranteed per instance and lease epoch by `tgsequence`. There is no ordering across instances.
- **Completeness depends on the source.** Every trigger declares `sourceReplay` in its annotation, and the value is
  shown at review and in the definition:

  | `sourceReplay` | Examples | What ToolGate can promise |
  |---|---|---|
  | `EVENT_LOG` | A broker consumer with offsets (Kafka, NATS JetStream), database change streams, append-only audit or event-feed APIs with a stable cursor | **Recovers every source event the source still retains,** from the last committed checkpoint. Events the source has expired before recovery are lost, and the trigger reports that through a `gap` it detects from the offset |
  | `STATE_SNAPSHOT` | A folder that can be listed, a mailbox (IMAP UIDs, mail APIs with `since` or delta queries), a REST collection | **Recovers the current state, not every intermediate event.** After downtime or a dropped notification, `reconcile()` reports what exists now that wasn't reported. Something created **and** deleted (or changed and changed back) while the trigger was not observing is never reported |
  | `NON_REPLAYABLE` | Fire-and-forget notifications with no way to re-read them: broadcast WebSocket feeds, OS notifications for transient state, UDP-style signals | **Best effort.** Events are lost if they arrive while the process is down, before `emit()` reaches the outbox, or after `emitTimeoutMs` under backpressure. Every loss the executor can detect is counted (`dropped`, `emitTimeouts`) and raises `Degraded`; undetectable losses are documented as possible. Bounded buffers cannot promise zero loss |

  - The samples are `STATE_SNAPSHOT`:
    - `mail-watch`;
    - `mail-idle`: an IMAP message expunged before reconcile is not reported, and a `UIDVALIDITY` change re-reports the
      mailbox's current messages once under new ids;
    - `inbox-watch`: a file created and deleted while offline is not reported, and rapid rewrites coalesce into the
      versions seen at reconcile time.
  - The processor warns on `EVENT_LOG` or `STATE_SNAPSHOT` without `reconcile()` or without a `position` on events.
  - The review screen and console show the class and its guarantee sentence verbatim.

### 6.6 Checkpoints: contiguous barrier, two durability levels

Events become durable out of order: emits complete asynchronously, and a later emit can be fsynced before an earlier
one. A checkpoint must therefore never be advanced from an individual completion. The SDK and supervisor implement a
**contiguous checkpoint barrier**:

1. Every `EMIT` carries its `emitSeq` (section 6.2.1) and optionally a `position` (the source position after that
   event).
2. **Every emit outcome falls into exactly one class:**

   | Class | Causes | Effect on the barrier |
   |---|---|---|
   | `LOCAL_DURABLE` | Written and fsynced to the outbox | Resolves it at `LOCAL` |
   | `RETRYABLE_REJECTION` | Rate limit, outbox full or `BackpressureTimeout`, local storage error. At Control: `429`, a temporary storage error | **Blocks the barrier.** It never advances past this emit. The SDK retries the same event (same `id`) with full-jitter backoff until it succeeds, becomes terminal, or the process stops. If the process stops, the committed checkpoint is still before this event, so the source re-delivers it on restart |
   | `TERMINAL_REJECTION` | Fails the signed schema or size limit, event type disabled, a declared drop policy (`DROP_*`, loss-tolerant types only). At Control: re-validation failure | Depends on the trigger's reviewed `onTerminalRejection` policy (below) |

3. **`onTerminalRejection`** is declared in the annotation and shown in the security delta:
   - **`HALT`** (default): the barrier stays before the rejected event. The instance goes to
     `Failed (TerminalRejection)` with the event `id`, reason and position. Nothing after it is checkpointed. Fixing
     the trigger (a new package version) and `Run` replays from the last committed checkpoint, so nothing is lost.
   - **`SKIP_AND_RECORD`** (explicitly approved): the supervisor writes a **loss record** to the outbox in place of the
     event, with its own `outboxSeq`. The record is a CloudEvent of type `io.ololabs.toolgate.trigger.event.rejected.v1`
     with the rejected event's `id`, reason, position and data digest, but not the data. The rejected emit is resolved
     **only when the loss record is** `LOCAL_DURABLE`. The loss is counted and raises `Degraded (EventsRejected)`.
     Required for `DROP_*` policies.
4. **Local watermark:** the highest `emitSeq` such that every earlier emit is `LOCAL_DURABLE` or represented by a
   durable loss record. The checkpoint committed at `LOCAL` is the `position` (or explicit `CHECKPOINT`) of the highest
   emit at or below it.
5. **Accepted watermark:** built from Control's acknowledgements, in `outboxSeq`, and translated by the supervisor.
   - An outbox record counts only when Control has **accepted** it: stored the event, or stored the loss record as an
     explicit terminal disposition.
   - When Control itself rejects an event terminally on re-validation, it stores a disposition
     `{source, id, disposition: REJECTED_TERMINAL, reason}` and returns it in `TriggerSyncAck.dispositions`. That
     record counts toward the accepted watermark **only** if the instance's policy is `SKIP_AND_RECORD`. Under `HALT`,
     Control records the disposition, sets the instance `Failed (TerminalRejection)`, and the accepted watermark stops
     before it.
   - A rejected event never advances the accepted watermark without a disposition recorded by Control.

| Level | Meaning | Used for |
|---|---|---|
| `LOCAL` | Every earlier event is fsynced in this executor's outbox | Restart **on the same executor**. The outbox survives the restart and will still deliver those events |
| `ACCEPTED` | Every earlier event is stored by Control (effectively-once) | **Server failover to another host**: Control persists only `ACCEPTED` checkpoints. Also client recovery after outbox loss (reinstall, disk wipe), where the client asks Control for its last `ACCEPTED` checkpoint |

- An explicit `checkpoint(c)` call is the same as attaching `c` as the position of the most recent emit. It obeys the
  same barrier. It never commits ahead of an unresolved earlier emit.
- POLL: the runtime emits each `check()`'s events and then the returned checkpoint, under the same barrier.
- Checkpoints never contain secrets. The SDK refuses a checkpoint that contains a value redeemed through
  `SECRET_REDEEM` (the same redaction layer as log frames).
- Tests: property tests with randomized completion order, retryable and terminal rejections (local and at Control) and
  crashes. Each run must show:
  - no checkpoint ever passes a retryable rejection, or a terminal rejection without a durable (`LOCAL`) or accepted
    (`ACCEPTED`) loss record;
  - under `HALT`, no checkpoint passes a terminal rejection at all.

  The review's case is a fixture: A accepted, B rejected retryable, C accepted, then restart. B must be re-emitted.

#### 6.6.1 Executable state-machine specification (SDK ↔ supervisor ↔ Control)

This is the normative model. Implementations in the SDKs, the supervisor crate and Control must match it, and the
model itself is checked mechanically (see *Verification* below).

**Per-event states.** Each event is tracked by `emitSeq` in the SDK, by `(leaseEpoch, outboxSeq)` in the executor and
in Control, and by `(source, id)` in the event store.

| State | Owner | Durable? | Meaning |
|---|---|---|---|
| `EMITTED` | SDK | No | `emit()` called; `emitSeq` assigned |
| `RETRY_PENDING` | SDK | No | Retryable rejection; the SDK holds the event and retries it |
| `HALTED` | Supervisor | Yes (instance condition) | Terminal rejection under `HALT` |
| `LOCAL_DURABLE` | Supervisor | Yes (outbox) | Record with `outboxSeq` fsynced |
| `LOSS_LOCAL` | Supervisor | Yes (outbox) | Terminal rejection under `SKIP_AND_RECORD`; loss record fsynced |
| `STORED` | Control | Yes (DB) | Event, dedupe key and router outbox row committed |
| `DISPOSED` | Control | Yes (DB) | Terminal disposition recorded by Control (loss record stored, or Control-side rejection) |
| `ACKED` | Supervisor | Yes (outbox truncation) | Executor received the ack, and the record may be purged |
| `FENCED` | Control | — | Record from an epoch Control no longer accepts (see *Lease epochs*) |

**Legal transitions and their atomic persistence invariant.** Each transition is one atomic step; nothing else is
persisted in between.

| # | Transition | Atomic step | Invariant it preserves |
|---|---|---|---|
| T1 | `EMITTED → LOCAL_DURABLE` | One outbox append containing `{leaseEpoch, outboxSeq, event}`, then fsync. There is **no separate sequence counter**: after recovery, the next `outboxSeq` is the last durable record + 1 | **S1:** an `outboxSeq` exists if and only if its record is durable. Sequences are gap-free per epoch |
| T2 | `EMITTED → RETRY_PENDING → EMITTED` | Nothing persisted | **S2:** a retryable event holds the barrier |
| T3 | `EMITTED → LOSS_LOCAL` | One outbox append of the loss record, then fsync | S1, plus **S3:** a terminal rejection resolves the barrier only through a durable loss record |
| T4 | `EMITTED → HALTED` | Instance condition persisted. No record appended | **S4:** nothing after a halted event is ever checkpointed |
| T5 | Advance the `LOCAL` checkpoint | Write `{leaseEpoch, afterOutboxSeq, state}` to a temp file, fsync, atomic rename | **L:** `afterOutboxSeq` ≤ the highest contiguous `outboxSeq` whose records are `LOCAL_DURABLE` or `LOSS_LOCAL` |
| T6 | `LOCAL_DURABLE/LOSS_LOCAL → STORED/DISPOSED` | One DB transaction: dedupe-key insert (or find the existing one), event or disposition row, router outbox row, and the per-`(instance, epoch)` `acceptedOutboxSeq` recomputed as contiguous | **A:** `acceptedOutboxSeq` = the highest n such that every `outboxSeq` 1..n is `STORED` or `DISPOSED` under `SKIP_AND_RECORD`. **D:** at most one stored event per `(tenant, source, id)` |
| T7 | Advance the `ACCEPTED` checkpoint (server) | One DB transaction: upsert the checkpoint with `afterOutboxSeq ≤ acceptedOutboxSeq` (rejected otherwise) | **C:** a Control checkpoint never passes `acceptedOutboxSeq` |
| T8 | `STORED/DISPOSED → ACKED` | The executor truncates the outbox up to the acked `outboxSeq` (fsync), and only then translates the ack to `ACCEPTED_UPTO` | **P:** a record is purged locally only after Control's commit is known |
| T9 | Any record → `FENCED` | No state change at Control. The record is rejected in the ack. **No dedupe key is written** for a fenced record | **F:** fenced records never change `acceptedOutboxSeq`, storage or the dedupe index. They cannot suppress a later replay of the same `id` |
| T10 | Outbox pruning | The executor deletes records only when they are `ACKED` (T8), or `FENCED` with a non-compromise reason, or under T13. One truncation plus fsync | **P2:** a record that is neither acknowledged nor fenced is never pruned. Outbox overflow never prunes; it applies backpressure (section 6.7) |
| T11 | Control retention expiry | The watchdog purges event payloads, then dedupe tombstones after their window, in bounded batches. It never changes `acceptedOutboxSeq`, Control checkpoints or the operation ledger (section 14.3) | **R:** retention changes only *what can be read and deduped*, never *what has been accepted or acted on* |
| T12 | Checkpoint migration (`checkpointSchemaVersion` change on upgrade) | Either `migrateCheckpoint(old) → new` written atomically (T5 or T7 style) **together with** the new package digest, or the checkpoint is discarded, which forces a full `reconcile()`. Never both an old-format and a new-format checkpoint live | **M:** after an upgrade, the position is either a faithful migration or the beginning, never a guess |
| T13 | Executor removal (`Remove`, uninstall, Tool Host deregistered) | `Removing`: stop the process, **drain** the outbox until empty or 24 h, then record `Removed` with the count of records not drained (an `UnrecoverableGap` lower bound for `NON_REPLAYABLE`), then delete local state | **X:** removal never silently discards queued records; any loss is counted |
| T14 | Reinstallation (same executor ID, empty local state) | A new lease epoch is issued. The executor fetches the instance's last `ACCEPTED` checkpoint from Control and starts there. Old-epoch records it no longer has are simply absent | **I:** a reinstalled executor never resumes from a position Control hasn't accepted |
| T15 | Producer quarantine (compromise revocation, below) | One DB transaction: set `distrustFrom` on the producer; mark matching `STORED` events `QUARANTINED`; cancel their pending deliveries | **Q:** no delivery, action or replay is created from a quarantined event |

**Crash and failure cases** (each one is a required test):

| Fault | Recovery | Outcome |
|---|---|---|
| Crash after the supervisor chose the next `outboxSeq` in memory, before fsync | No record exists (S1). The SDK future was never completed, and the trigger process died with the supervisor. The checkpoint is before the event | `EVENT_LOG`/`STATE_SNAPSHOT`: re-emitted from the source with the same `id`. `NON_REPLAYABLE`: lost, and counted as `possibleLossOnCrash` from the restart |
| Crash after fsync, before `EMIT_ACK` | The record is durable and will be sent. The SDK never saw it as durable, so the checkpoint is before it | Re-emitted with the same `id` after restart; Control dedupes it (D) |
| Control commits (T6), the ack is lost | The executor re-sends. Control finds the dedupe key and acks the existing record | Stored once; ack delivered later |
| Crash between Control's commit and computing `acceptedOutboxSeq` | Impossible: both are in T6's transaction | — |
| Executor crash between the ack and truncation (T8) | Records are re-sent and acked as duplicates | No duplicate storage |
| Disk loss on a client (outbox and local checkpoint gone) | The client fetches its last `ACCEPTED` checkpoint for the instance from Control and resumes from it | Replayable classes recover; `NON_REPLAYABLE` loses whatever was only local (counted as an `UnrecoverableGap` lower bound, below) |
| SDK assigned an `emitSeq`, then crashed with no durable `outboxSeq` | The new process starts `emitSeq` at 1, and the source re-delivers the event | The replayed event gets a **new** `emitSeq` and a **new** `outboxSeq`, but the **same** stable `id`. Control stores it once, keyed by `(source, id)`. No identity derives from `emitSeq` or `outboxSeq` |

**Lease epochs with queued records:**

- Every record carries the epoch it was written under. A new epoch starts `outboxSeq` at 1.
- **Same executor, non-revocative epoch end** (config change, `Stop` then `Run`, a client lease renewed as a new
  epoch): Control accepts **drain** of the older epoch's queued records from the same executor for up to 7 days. They
  keep their own `(epoch, outboxSeq)` order and `acceptedOutboxSeq`, so nothing a device queued offline is lost
  through reconfiguration.
- **Server failover** (a lease moves to a different executor). The old host may hold records that are durable locally
  but not yet accepted by Control. The recovery contract is:
  1. The old epoch is `FENCED` (F). Its records are rejected **without writing dedupe keys**, so they cannot suppress
     the same `id` arriving from the new host.
  2. The new host starts from the old epoch's last `ACCEPTED` checkpoint. By invariant C, that is at or before the
     first record Control didn't accept, so **the new host's source position cannot advance past any missing record**.
     Its checkpoint barrier then applies under the new epoch.
  3. What it recovers depends on the source class:
     - `EVENT_LOG`: every missing record the source still retains.
     - `STATE_SNAPSHOT`: the current state, so a missing record is re-reported if its item still exists.
     - `NON_REPLAYABLE`: nothing. The gap is recorded (below).
  4. Old-host records accepted *before* the fence keep their storage and dedupe keys. Re-emitting them under the new
     epoch is acknowledged as a duplicate, which is correct.
- **Normal revocation** (assignment removed, kill switch for a non-security reason, device decommissioned): older epochs
  are `FENCED`. Events **already stored** stay valid and their deliveries proceed under section 14.2's checks.
- **Compromise revocation** (device or Tool Host revoked with reason `Compromised`, certificate compromise, or an
  admin's *distrust producer* action). The admin sets `distrustFrom`; the default is the start of the producer's
  current lease epoch, and it can be set earlier. Then (T15):
  - every queued record from that producer is fenced and never trusted;
  - every `STORED` event from that producer with Control `receivedAt ≥ distrustFrom` becomes `QUARANTINED`: it is not
    delivered, not replayed and not acted on, and its pending deliveries are `Skipped (ProducerDistrusted)`;
  - invocations already started from quarantined events are listed for review. They are not undone (section 14.4.1
    limits);
  - being written before the revocation is **not** a reason to trust an event. Only events received before
    `distrustFrom` keep their status, and the admin can move `distrustFrom` earlier at any time.

**Unavoidable gaps (documented, not hidden).** If a Tool Host running a `NON_REPLAYABLE` trigger is permanently lost,
or a client's disk is lost, before its outbox reaches Control:

- those events are gone, and no protocol can recover them;
- Control records an `UnrecoverableGap` on the instance with a **lower bound** of `lastReportedOutboxSeq −
  acceptedOutboxSeq` for that epoch, taken from the last snapshot (the true number may be higher);
- the instance shows `Degraded (UnrecoverableGap)` until an admin acknowledges it.

For replayable classes the same event means *recovered from the last `ACCEPTED` checkpoint*, with the class's normal
guarantee.

**Verification (gate G-STATE):**

1. **TLA+ specification** `docs/specs/trigger_delivery.tla` of the states and transitions T1–T15, with:
   - crashes at every step, lost and duplicated messages, and epoch changes;
   - outbox pruning, retention expiry, checkpoint migration, removal and reinstallation, failover with unaccepted
     old-host records, and compromise quarantine.

   TLC model-checks it for small bounds (3 events, 2 epochs, 2 executors) against the invariants S1–S4, L, A, C, D,
   P, P2, F, R, M, X, I and Q, and the liveness property *every retained source item of a replayable source is
   eventually `STORED` under fair scheduling*.
2. **Stateful property tests** generated from the same model: `proptest` state machines for the supervisor crate, and
   jqwik stateful tests for Control ingest. They inject crashes at every persistence point, ack loss and duplication,
   out-of-order completions, epoch changes with queued records, and retryable and terminal rejections on both sides.
   After every step they assert that **no source checkpoint (`LOCAL` or `ACCEPTED`) and no `acceptedOutboxSeq` advances
   across an unresolved event**.
3. **A deterministic simulation test** wiring the real SDK runtime, supervisor and Control ingest, with fault
   injection, runs nightly with random seeds. Failing seeds become permanent fixtures.

### 6.7 Backpressure and overflow

| Outbox fill | POLL | PUSH, `overflow: BLOCK` (default) | PUSH, `DROP_*` (declared, loss-tolerant types only) |
|---|---|---|---|
| < high watermark (80%) | Normal | Normal | Normal |
| ≥ high watermark | Skips checks until below the low watermark (50%); `Degraded=True, reason=Backpressure` | `emit()` futures stay pending, which slows the author's listener; `Degraded=True, reason=Backpressure` | Drops by policy; counts `dropped`; `Degraded=True, reason=EventsDropped` |
| Full | Same | After `emitTimeoutMs` (30 s), `emit()` reports `BackpressureTimeout`, a `RETRYABLE_REJECTION` that blocks the checkpoint barrier (section 6.6). The SDK keeps retrying; the author may also rely on `reconcile()` | Terminal rejection with a loss record (`SKIP_AND_RECORD` is mandatory for `DROP_*`) |

Outbox bounds: client 1 000 events / 1 MiB; Tool Host 10 000 events / 8 MiB per host. `STATE` events have a reserved
slice and are never dropped.

### 6.8 Health and shutdown

- **Readiness:** `READY` within `startTimeoutMs` (default 60 s), or the start counts as a failure.
- **Liveness:** the SDK runtime sends `HEARTBEAT` every 10 s from its own thread. Three missed heartbeats mean the
  process is hung, so the supervisor restarts it.
- **Author health:** `reportHealth(DEGRADED, "imap reconnecting")` maps to the `Degraded` condition with the author's
  reason. `UNHEALTHY` for longer than 5 minutes causes a restart.
- **Shutdown:** `STOP` → the SDK calls `stop()` and cancels `runInBackground` tasks → `EXIT`. After `stopGraceMs`
  (default 10 s, max 30 s), the executor sends SIGTERM, then SIGKILL 5 s later. Events emitted after `STOP` are
  rejected.

### 6.9 Restarts

Restarts use exponential backoff with full jitter: `sleep = random(0, min(5 min, 5 s × 2^attempt))`. After 5 failed
starts in 10 minutes, the instance goes to `phase: Failed` (`reason: CrashLoop`). It stays there until the config
revision changes or an admin action `RUN` resets the budget. A successful run of 10 minutes also resets it.

### 6.10 Sandbox posture

Server triggers run in the Tool Host's `OCI_SANDBOX` tier with exactly the guarantees server tools have today. #230
has qualified worker and broker isolation for Linux x86_64 Python OCI pools (S11–S14, claims C1 and C5); other
runtimes and tiers are not qualified, so triggers claim nothing stronger than the pool they run in. A push trigger's native
library reaches the network only through `tcp().socketFactory` / `tcp().connect` for a declared destination. A pool
that refuses `CREDENTIALS_IN_SANDBOX` refuses it for triggers too.

## 7. Lease

| Parameter | Server | Client |
|---|---|---|
| `leaseDuration` | 60 s | Desired document expiry (default 24 h), so the device keeps working offline |
| `renewDeadline` (executor stops the trigger if not renewed by then) | 40 s | 24 h minus 5 min |
| Renewal | Every check-in ack | Every check-in ack |
| Reassignment | Only after `leaseDuration` + 5 s clock-skew grace | Never (device-bound) |

- `leaseEpoch` is a monotonic fencing number per instance. Control rejects any event, checkpoint, secret redemption,
  tunnel or broker capability carrying a lower epoch than the current one. `leaseToken` (256-bit random) is the bearer
  credential that proves the executor holds that epoch. It is checked like the consumed permit nonce (ADR 031).
- **Server singleton:** at most one live lease per instance. Failover goes to another `Ready` host in the pool at
  epoch `n+1`, resuming from the Control checkpoint and running `reconcile()`.
- **Revocation** (stop, remove, package suspended, Tool Host deregistered, device revoked) refuses renewal and lists
  the lease under `revoked`. Server instances stop within one check-in and at most at `renewDeadline`.
- Leases live in Control's database, using the same transaction model as the invocation ledger (ADR 016).
- **A lease authorizes running the trigger and reporting its events. Nothing else.** It is not a permission to
  execute protected work. In particular, the 24 h **client** lease exists so device triggers keep observing while
  offline. It does not let a device run tools, start actions or perform effects offline: every action is an online
  admission in Control, with its own policy decision and permit (section 14).
- **Run lease vs liveness.** Lease expiry and `Stale` detection are separate. `Stale` comes from missed check-ins
  (section 9.2) and changes only what the console shows. It never extends or revokes a lease by itself. Revocation is an
  explicit Control decision.

## 8. Trigger Sync protocol (one protocol for both executors)

```
admin ── TriggerAssignment / actions ──► Control ─┬─ package presence: fleet desired (client) / tool-hosts assignments (server)   [existing]
                                                  └─ TriggerDesiredDocument (signed, generation N) ──────────────► executor     [new, same for both]
executor ── EndpointCheckIn { …, triggers: TriggerSync } ──► Control     (the "ping")
executor ◄── EndpointCheckInAck { …, triggers: TriggerSyncAck } ──────   (the "pong": acks, leases, revocations)
```

### 8.1 Down: `TriggerDesiredDocument`

`GET /api/control/v1/endpoint/triggers/desired`, authenticated by the executor's certificate. It returns a compact JWS
signed by the organization deployment key (ADR 010). Tool Hosts get the verification key through tenant discovery; M0
confirms whether discovery already carries it.

```json
{
  "formatVersion": 1,
  "tenantId": "3f1c…",
  "executorId": "toolhost-a",
  "executor": "SERVER",
  "generation": 41,
  "issuedAtUnixMs": 1793413600000,
  "expiresAtUnixMs": 1793500000000,
  "instances": [
    {
      "instanceId": "c7b2…",
      "triggerId": "examples.mail-idle/new-message",
      "packageDigest": "sha256:…",
      "triggerDescriptorDigest": "sha256:…",
      "configRevision": 2,
      "config": { "user": "ap@example.com" },
      "bindingDigest": "sha256:…",
      "desiredState": "Running"
    }
  ]
}
```

- `desiredState` is the **resolved** per-instance value: `Running`, `Paused` or `Stopped`. Scopes and overrides never
  leave Control.
- An executor runs only instances that are in the newest valid, unexpired document **and** hold a live lease.
- An instance missing from a newer generation is stopped and removed, and its local state is deleted.
- `tenantId` and `executorId` must match the executor's enrollment. Executors without the `triggers.v1` capability
  get no document.

### 8.2 Up: `TriggerSync` in every check-in

```json
{
  "triggers": {
    "observedGeneration": 41,
    "instances": [
      {
        "instanceId": "c7b2…",
        "configRevision": 2,
        "leaseEpoch": 7,
        "phase": "Running",
        "conditions": [
          { "type": "Ready", "status": "True", "reason": "Started", "lastTransitionTime": "2026-10-31T10:02:11Z" },
          { "type": "Degraded", "status": "False", "reason": "AsExpected", "lastTransitionTime": "2026-10-31T10:02:11Z" }
        ],
        "lastEventAt": "2026-10-31T10:30:10Z",
        "lastReconcileAt": "2026-10-31T10:15:00Z",
        "counters": { "emitted": 17, "rejected": 0, "dropped": 0, "restarts": 0, "brokerCalls": 31 },
        "outbox": { "depth": 0, "oldestAgeMs": 0 },
        "checkpoint": { "leaseEpoch": 7, "afterOutboxSeq": 41, "level": "LOCAL", "version": 12,
                        "digest": "sha256:…", "sizeBytes": 38211, "inline": null }
      }
    ],
    "events": [ { "...": "CloudEvents, oldest unacknowledged first" } ],
    "pending": 0
  }
}
```

- **The snapshot** (`instances`) describes every instance the executor holds: ≤ 32 on a client, ≤ 256 on a Tool Host.
  It is authoritative for "what is running there", and it fixes any lost event. It is sent within the size budget in
  section 8.5. When it doesn't fit, it is paged, and a `snapshotDigest` lets Control detect a complete set.
- **`checkpoint`** in the check-in is **metadata only** for both executors: epoch, `afterOutboxSeq`, level, version,
  digest and size. The body goes inline (`inline`) only if it is ≤ 4 KiB. Otherwise it is uploaded through the
  chunked checkpoint transfer (section 8.6).
- **The events** are at most 64 or 48 KiB per check-in (the `RunEventBatch` bounds). When `pending > 0`, the ack asks
  for an immediate follow-up check-in, so a backlog drains without another endpoint.

### 8.3 Pong: `TriggerSyncAck`

```json
{
  "triggers": {
    "generation": 42,
    "acked":   [ { "instanceId": "c7b2…", "leaseEpoch": 7, "outboxSeq": 41 } ],
    "dispositions": [ { "instanceId": "c7b2…", "leaseEpoch": 7, "outboxSeq": 39, "id": "AAMk…", "disposition": "REJECTED_TERMINAL", "reason": "SchemaInvalid" } ],
    "retryLater": [ { "instanceId": "c7b2…", "leaseEpoch": 7, "fromOutboxSeq": 42, "retryAfterMs": 5000 } ],
    "leases":  [ { "instanceId": "c7b2…", "leaseEpoch": 7, "leaseToken": "…", "expiresAtUnixMs": 1793413660000 } ],
    "revoked": [ { "instanceId": "…", "reason": "AssignmentStopped" } ],
    "rejected":[ { "instanceId": "…", "leaseEpoch": 6, "reason": "StaleLeaseEpoch" } ]
  }
}
```

- **`acked`** is the highest contiguous `outboxSeq` per `(instanceId, leaseEpoch)` that Control has **resolved**:
  stored, or (only under `SKIP_AND_RECORD`) recorded as a terminal disposition.
- **`dispositions`** lists Control-side terminal rejections (section 6.6). Under `HALT`, `acked` stops below the
  disposition, and the instance is set `Failed (TerminalRejection)`.
- **`retryLater`** lists retryable rejections (`429`, temporary storage). The executor keeps those records and re-sends
  them after the delay. They are never acknowledged until stored.
- **`generation`** higher than `observedGeneration` means the executor should fetch the desired document.
- **`rejected`** tells the executor to drop the outbox entries of that `leaseEpoch` (records of other epochs, such as
  the current one, stay queued), stop the instance and resync. Reasons:
  `UnknownInstance`, `NotAssigned`, `StaleLeaseEpoch`, `StaleConfigRevision`.

### 8.4 Ingress rules

- The tenant and executor come from the mTLS certificate. IDs in the body are ignored for authorization.
- Events are accepted only for a lease issued to this executor, in this tenant, at the current epoch and config
  revision.
- Control re-validates `data` against the signed schema, enforces rate limits again (token bucket per instance and per
  tenant), and stores each event once per `(tenant, source, id)`.
- A snapshot entry for an instance Control didn't assign is recorded with the condition `Unexpected` and never trusted.
- An executor over a tenant limit gets `429` with `Retry-After`. It backs off, and its outbox applies section 6.7.

### 8.5 Check-in size budget (validated, not assumed)

**What `main` enforces today:**

- Check-in has **no dedicated byte limit**. Control's only bound is the global
  `quarkus.http.limits.max-body-size=65M` (`apps/control-plane/src/main/resources/application.properties`).
- The Tool Host reads Control replies up to **1 MiB** (`MAX_REPLY`, `apps/tool-host/src/control.rs`).
- `EndpointCheckIn` bounds item **counts** (`packages ≤ 1000`, `localTools ≤ 50`), not bytes.

Adding trigger data therefore needs an explicit byte budget.

**Budgets** (Control advertises `maxCheckInBytes` in every ack; operators may lower it, never raise it):

| Message | Hard limit | Trigger share |
|---|---|---|
| `EndpointCheckIn` request | 256 KiB serialized | `TriggerSync` ≤ 192 KiB, made of: `STATE` events first, then the snapshot (or a snapshot page) ≤ 96 KiB, then metadata for checkpoints **changed since the last ack** ≤ 16 KiB, then `FIRED` events ≤ 48 KiB |
| `EndpointCheckInAck` reply | 256 KiB (well under the Tool Host's 1 MiB reply cap) | `TriggerSyncAck` ≤ 128 KiB; leases are paged the same way as the snapshot when needed |

**Rules:**

- The executor fills the trigger share in the priority order above and stops before the limit. Anything left sets
  `pending > 0`, which triggers an immediate follow-up check-in.
- **Snapshot paging contract** (pages from different snapshots are never combined):
  1. **Freeze.** When a snapshot doesn't fit in one check-in, the executor freezes it as an immutable snapshot with a
     monotonic `snapshotGeneration` (per executor, persisted) and a `snapshotDigest` over the canonical (RFC 8785)
     serialization of the whole snapshot. Later inventory changes do not modify a frozen snapshot.
  2. **Identify every page.** Each page carries `{snapshotGeneration, snapshotDigest, page, pageCount, pageDigest}`.
     Control acks pages individually in `TriggerSyncAck.snapshotPages`.
  3. **Supersede and restart.** If the inventory changes before every page is acked, the executor freezes a **new**
     generation and restarts the transfer from page 1, and Control discards pages of older generations. To guarantee
     progress, an executor whose transfer is superseded 3 times in a row holds further snapshot changes (state is still
     tracked locally) until the current generation completes.
  4. **Apply only complete, verified snapshots.** Control assembles the pages of one generation, verifies every
     `pageDigest` and the overall `snapshotDigest`, and checks that the generation is greater than the last applied
     one. Only then does it replace its view, in one transaction. Until then it keeps the previous complete snapshot.
     A failed verification discards the generation and asks for a resend (`snapshotRequested`).
  5. **Events are unaffected.** Paging applies only to the snapshot. Events, acks and leases follow their own rules, so
     a slow snapshot transfer never blocks event delivery.
- Checkpoint **bodies** are never in the check-in unless they are ≤ 4 KiB (section 8.6).
- **Contract test in CI:**
  - every string in the check-in, ack and trigger schemas must declare `maxLength`, and every array `maxItems`; a
    schema without them fails the build;
  - a generator builds the **worst-case** check-in (32 client or 256 Tool Host instances, maximum-length strings,
    maximum events, all checkpoints changed) and asserts it serializes within the budget, with the paging rules applied;
  - the same test runs for the ack.

### 8.6 Chunked checkpoint transfer

Checkpoints larger than 4 KiB (up to 128 KiB for `STATE_SNAPSHOT`) move outside the check-in, on an authenticated,
resumable, chunked channel. Both Tool Hosts (for failover) and clients (for disk-loss recovery, section 6.6.1) upload
`ACCEPTED`-level checkpoints. Clients upload at most once per 5 minutes, or when a reconcile completes.

| Route (device or Tool Host mTLS certificate plus the instance's current `leaseToken`) | Purpose |
|---|---|
| `POST /api/control/v1/endpoint/triggers/instances/{instanceId}/checkpoint-uploads` `{version, leaseEpoch, afterOutboxSeq, level, sizeBytes, digest}` | Start an upload. Idempotent on `(instanceId, version)` and returns `uploadId` and `chunkBytes` (64 KiB) |
| `PUT …/checkpoint-uploads/{uploadId}/chunks/{n}` (body ≤ 64 KiB, chunk digest header) | Upload a chunk. Idempotent; re-sending a chunk is safe |
| `POST …/checkpoint-uploads/{uploadId}:commit` | Make the checkpoint current (rules below) |
| `GET …/checkpoint` and `GET …/checkpoint/versions/{version}/chunks/{n}` | Fetch metadata, then the body in chunks, for failover and reinstallation |

**Commit is one database transaction.** It verifies all of the following:

- the assembled size and SHA-256 digest equal the declared ones;
- the lease is current for this executor and epoch, and the `leaseToken` matches;
- `version` is greater than the current version;
- for `ACCEPTED`, `afterOutboxSeq ≤ acceptedOutboxSeq` (invariant C).

Only then does it make the new **body, digest and version current together**. The previous version stays readable
until the commit succeeds. A failed or abandoned upload changes nothing, and it expires after 1 hour.

**Lease changes during an upload:**

- An upload is bound to the `(executorId, leaseEpoch, leaseToken)` that started it. Each chunk and the commit re-check
  that binding.
- If the lease expires, is revoked or moves to another executor at **any** point, including between the last chunk and
  the commit, then:
  - the commit is refused with `StaleLeaseEpoch`;
  - the upload is marked `Abandoned` and its chunks are deleted;
  - Control's current checkpoint does **not** advance.
- The next authorized executor, whether a failover host, the same executor under a new epoch, or a reinstalled one,
  starts from the **last committed** `ACCEPTED` checkpoint (T14). It never resumes or completes another epoch's upload.
- An upload already committed before the lease change stays current: it was valid when committed, and invariant C
  still holds. Uploads cannot be resumed across epochs; a new epoch starts a new upload.

Downloads are verified against the digest before use. A partial or mismatched body is never used: the executor
retries, or starts from the beginning (T14).

The check-in carries only `{version, digest}` once the body is committed. Control flags a mismatch between the
reported and committed digests as `Degraded (CheckpointSyncPending)` until it resolves.

## 9. Resource model and status

### 9.1 `TriggerAssignment`

```yaml
apiVersion: toolgate.olo/v1
kind: TriggerAssignment
metadata:
  name: ap-invoices-mail
  uid: a-21                 # server-assigned
  generation: 3             # increments on every spec change
  resourceVersion: "W/\"9f2c\""  # ETag for If-Match
  labels: { team: finance }
spec:
  trigger: examples.mail-idle/new-message
  packageVersion: 1.0.0
  target:
    executor: SERVER
    pool: finance-tool-hosts
  config: { user: ap@example.com }
  binding:
    serviceProfile: imap-readonly
    globals: { imapHost: imap.example.com }
    secrets: { imap-password: "vault://finance/ap-imap" }   # reference only
  desiredState: Running
  strategy:
    rollingUpdate: { maxUnavailable: 25% }                 # package upgrades and config changes on client groups
status:
  observedGeneration: 3
  instances: { desired: 1, ready: 1, running: 1, paused: 0, stopped: 0, failed: 0, stale: 0, unsupported: 0 }
  conditions:
    - { type: Available, status: "True", reason: AllInstancesRunning, lastTransitionTime: "2026-10-31T10:02:11Z" }
    - { type: Progressing, status: "False", reason: RolloutComplete, lastTransitionTime: "2026-10-31T10:02:11Z" }
```

A client assignment uses `target: { executor: CLIENT, devices: [...], deviceGroups: [...], excludeDevices: [...] }`.

### 9.2 `TriggerInstance` (read-only)

| Field | Meaning |
|---|---|
| `spec.desiredState` + `spec.desiredStateSource` | Resolved value, and where it came from: `Assignment`, `Group:<name>`, `Device` or `Instance` |
| `status.phase` | `Pending` (install or lease wait), `Starting`, `Running`, `Paused`, `Stopped`, `Failed`, `Unsupported`, `Removed` |
| `status.conditions` | `Installed`, `Leased`, `Ready`, `Degraded` (reasons: `Backpressure`, `EventsDropped`, `AuthorReported`, `CheckFailures`, `BrokerRefused`), `Reconciled`, `Stale`, `Unexpected` |
| `status.observedGeneration`, `configRevision`, `leaseEpoch`, `lastSeenAt`, `lastEventAt`, `lastReconcileAt`, `counters`, `outbox` | As reported, plus the server-side `lastSeenAt` |

Control derives `Stale=True` after 3 missed check-ins or 5 minutes, whichever is longer, so a disconnected executor is
never shown as running. For server instances it also derives `FailingOver` and `Unplaced` reasons on the `Leased`
condition.

## 10. Admin API

### 10.1 Conventions

- Base path `/api/control/v1/triggers`. Routes go into `control-v1.proposed.yaml` first, and into the served spec when
  implemented.
- Errors are RFC 9457 `application/problem+json` with a stable `type` URI and `instance` correlation ID. Cross-tenant
  and unknown IDs give the identical `404`.
- Updates require `If-Match` with the `resourceVersion` ETag, and a stale ETag returns `412`. Every mutating call
  accepts `Idempotency-Key`, and a replay returns the original response.
- `PATCH` uses JSON Merge Patch (`application/merge-patch+json`). Bodies may be JSON or YAML (`application/yaml`).
- `?dryRun=true` on every mutating call runs full validation and returns the would-be result, including the list of
  instances that would change.
- Lists use opaque cursor pagination (`pageToken`, `pageSize` ≤ 200) and label selectors (`?labelSelector=team=finance`).

### 10.2 Routes

| Method and route | Permission | Purpose |
|---|---|---|
| `POST /api/control/v1/packages/uploads` (existing) | `package.upload` | Upload a `.tgpkg` with triggers |
| existing review / enable routes | existing | Approve and enable; the security delta covers trigger capabilities and modes |
| `GET /triggers/definitions` | `trigger.read` | Definitions in enabled versions, with mode, executors, schemas |
| `POST /triggers/assignments` | `trigger.manage` | Create (`DEPLOY` with the initial desired state) |
| `GET /triggers/assignments`, `GET /triggers/assignments/{name}` | `trigger.read` | List or read, with `status` |
| `PATCH /triggers/assignments/{name}` | `trigger.manage` | Change spec: config, binding, `desiredState`, `packageVersion` (starts a rollout) |
| `DELETE /triggers/assignments/{name}` | `trigger.manage` | Remove everywhere |
| `POST /triggers/assignments/{name}:action` | `trigger.manage` on each target in scope | Scoped lifecycle actions (section 10.3) |
| `POST /triggers:action` | `trigger.manage` on each target in scope | The same actions across assignments, by selector |
| `POST /triggers/assignments/{name}:rollback` | `trigger.manage` | Return to a previous `packageVersion` revision |
| `GET /triggers/operations?assignment=&action=&state=` | `trigger.read` | Operation ledger entries with their attempt history (section 14.3) |
| `POST /triggers/operations/{operationKey}:redrive` | `trigger.redrive` | First attempt of effect 0 for an operation that never started; reason required |
| `POST /triggers/operations/{operationKey}/effects/{effectId}:retry` | `trigger.redrive` | New attempt of the **same** intended effect (same `effectId`); allowed only after a definite failure or a reconciliation showing it was not applied |
| `POST /triggers/operations/{operationKey}:repeat` | `trigger.repeat` (+ maker/checker where required) | A **new** intended effect: new `repeatId` and `effectId`; reason and acknowledgement of earlier outcomes required; refused while an earlier effect is unreconciled `OUTCOME_UNKNOWN` |
| `POST /triggers/producers/{executorId}:distrust` | `trigger.manage` + `device.revoke` | Set `distrustFrom` and quarantine that producer's events (section 6.6.1, T15) |
| `GET /triggers/instances?assignment=&executor=&executorId=&phase=&condition=` | `trigger.read` | Instances with resolved desired state and status |
| `GET /triggers/instances/{id}/events?pageToken=&type=&since=` | `trigger.read` | Stored CloudEvents |
| `GET /triggers/executors/{executorId}` | `trigger.read` | Last raw snapshot from one Tool Host or device |

`trigger.read` and `trigger.manage` are new permissions, checked against the Device Group or pool of each target.
Setting secret bindings also needs the existing deployment-binding secret permission.

### 10.3 Lifecycle actions per group, device or instance

| Action | Effect | Executor | Lease | Local state |
|---|---|---|---|---|
| `Deploy` | Add targets (devices, Device Groups or a pool) with an initial desired state | Install, self-test, then start or hold | Issued if Running or Paused | Created |
| `Run` | Override → Running | Start, or `RESUME` | Issued or renewed | Kept |
| `Pause` | Override → Paused | `PAUSE` → `onPause()` (default: `stop()`); process and lease kept; no checks, emits or broker calls | Kept | Kept |
| `Stop` | Override → Stopped | `STOP` → process exits, package stays installed | Released | Kept, so `Run` resumes from the checkpoint |
| `Remove` | Remove targets; a device inside a targeted group goes into `excludeDevices` | Stop, delete state, report `Removed`; uninstall the package if unused | Revoked | Deleted (and the server checkpoint) |
| `Clear` | Delete overrides at the scope | As for the resulting state | | |

```yaml
# POST /api/control/v1/triggers/assignments/finance-inbox-pdf:action
action: Pause
scope: { deviceGroups: [finance-desktops] }       # or devices: [...], or instances: [...]
reason: Month-end freeze
```

```yaml
# POST /api/control/v1/triggers:action   (across assignments)
action: Stop
selector:
  deviceGroups: [finance-desktops]
  triggers: ["examples.inbox-watch/*"]
reason: Incident 4411
```

**Rules:**

- The effective desired state resolves as instance or device override, then group override, then the assignment
  default.
- If a device is in several targeted groups with different overrides, the most restrictive wins: Stopped, then Paused,
  then Running. A group membership change therefore never starts what an admin stopped.
- An action is atomic. Every target is validated (tenant, permission, membership) before anything changes. The action
  bumps only the affected executors' generations and returns every affected instance with its old and new effective
  state. It is audited with the actor, scope, reason and that list.
- Overrides survive config changes and package upgrades, and group overrides apply to devices that join the group
  later.
- A server assignment has one instance, so the pool and instance scopes are the same. Pinning to a specific host is not
  in v1.

### 10.4 Package upgrades and config rollouts

- Changing `spec.packageVersion` or `spec.config` on a client assignment rolls out per `strategy.rollingUpdate`. At
  most `maxUnavailable` instances are not `Ready` at a time, and the rollout advances when replaced instances report
  `Ready`.
- `Progressing=True` while rolling. The rollout halts with `Progressing=False, reason=RolloutStalled` when replaced
  instances fail. The admin can `:rollback`, or `Pause` to freeze.
- Client package delivery still uses fleet generations and the canary machinery from ADR 010 and ADR 014.
- A server assignment upgrades by stopping the old instance (graceful stop, checkpoint flushed) and starting the new
  version from that checkpoint, under a new lease epoch.

## 11. Schedule triggers (Quartz)

A schedule is a `TriggerAssignment` whose source is `schedule` instead of a packaged `trigger`. Exactly one of the two
is set. It runs in Control (`executor: CONTROL`), needs no package and no lease, and produces events through the same
store, subscriptions and actions as any other trigger.

```yaml
apiVersion: toolgate.olo/v1
kind: TriggerAssignment
metadata:
  name: ap-daily-report
spec:
  schedule:
    cron: "0 0 9 ? * MON-FRI"            # Quartz cron (seconds field first); or interval: PT15M
    timeZone: Asia/Kolkata               # IANA zone; DST handled by Quartz CronTrigger
    startAt: 2026-11-01T00:00:00+05:30   # optional
    endAt: null                          # optional
    misfirePolicy: FireOnceNow           # FireOnceNow | Skip
    startingDeadline: PT10M              # a fire later than this after its scheduled time is skipped (CronJob semantics)
    jitter: PT0S                         # optional random delay, ≤ 5 min, to spread load
  eventType: org.acme.reports.ap-daily.v1
  payloadSchema:                         # JSON Schema for the fixed payload
    type: object
    required: [report]
    properties: { report: { type: string } }
  payload: { report: ap-daily }
  desiredState: Running
  actions: [ ... ]                       # optional, section 14
```

**Implementation:**

- **Engine.** `quarkus-quartz`. On PostgreSQL it uses a clustered JDBC job store (`jdbc-cmt`), so one Control replica
  fires each trigger. The Quartz tables are part of the fresh initial schema (section 0), not a migration.
- **Quartz is a derived cache, never the source of truth.** The canonical schedule is the `TriggerAssignment` row
  (spec, `metadata.generation`, effective desired state). Quartz holds only the job key `<tenantId>/<assignmentUid>`
  and job data `{tenantId, assignmentUid, generation}`. No schedule details, payload or tenant data are stored in
  Quartz tables.

#### 11.1 Schedule reconciliation controller

There is a gap between committing a schedule change to the database and registering it with Quartz. A crash in
between, a failed Quartz call, or a manual Quartz-table edit would otherwise leave them out of sync. The controller
closes it:

```
TriggerAssignment write (spec or lifecycle) ─┐ same transaction
                                             └► trigger_config_outbox {tenant, assignmentUid, generation}
Schedule Reconciler ─ claims outbox rows ─► upsert / pause / unschedule the Quartz job to match generation
                    └────────────────────► status.schedule.observedGeneration = generation; outbox row done
```

- **Write path.** Every change that affects a schedule bumps `metadata.generation` and inserts a
  `trigger_config_outbox` row **in the same transaction**: spec edit, `Pause`, `Run`, `Stop`, `Remove`, tenant
  suspension.
- **Reconcile.** The reconciler (one active instance, chosen with a PostgreSQL advisory lock; a single thread on
  SQLite) applies the **latest** generation for that assignment idempotently:
  - create or replace the job and trigger (cron, time zone, start and end);
  - pause or resume it;
  - unschedule or delete it.

  Then it writes `status.schedule.observedGeneration`. Applying an older or equal generation is a no-op. On failure,
  the outbox row is retried with full-jitter backoff, and the assignment shows `Ready=False, reason=ReconcileFailed`.
- **Full reconciliation** at startup and every 5 minutes compares every Quartz job with every canonical schedule
  assignment, and repairs any discrepancy:
  - a missing job is created;
  - an orphaned job (unknown or removed assignment, or another tenant's key prefix) is deleted;
  - a mismatched cron, time zone, pause state or generation is replaced.

  Each repair is counted (`toolgate.trigger.schedule.repairs`) and audited.
- **Quickstart (SQLite).** The Quartz JDBC store does not support SQLite, so Quickstart uses the RAM store. The
  startup full reconciliation rebuilds every job. Missed fires are computed from the last row in
  `trigger_schedule_fires` and the misfire policy. Quickstart is single-node, so this is safe.

#### 11.2 Fire-time guard

A Quartz fire is a **request to fire**, not an authority. The fire job runs one transaction:

1. Lock the assignment row (`SELECT … FOR SHARE`) and check all of the following:
   - the tenant is active;
   - the assignment exists;
   - `jobData.generation == metadata.generation`;
   - the effective desired state is `Running`;
   - the scheduled fire time is a valid fire time of the **canonical** schedule;
   - the fire is within `startAt`, `endAt` and `startingDeadline`.
2. If any check fails, record a `trigger_schedule_fires` row with `Skipped` and the reason (`StaleGeneration`,
   `NotRunning`, `Removed`, `OutsideWindow`, `DeadlineExceeded`), and enqueue a reconciliation for that assignment.
   No event is produced.
3. Otherwise, insert the CloudEvent into `trigger_events`, an event-outbox row (section 12) and the fire-history row.
   The event `id` is `schedule:<assignmentUid>:<scheduledFireTimeUtcMillis>`, so a double fire (failover, misfire
   handling, a reconciler race) is stored once.

No action or other side effect runs on the Quartz thread.

- **Event attributes.** `time` is the scheduled fire time, and `tgfiretime` is the actual time.
- **Concurrency with changes.** Every write to an assignment takes the row with `FOR UPDATE`, and the fire transaction
  takes it `FOR SHARE`. A fire and a generation change, `Pause` or `Stop` are therefore serialized. The fire sees
  either the old committed state (and fires under the old generation, before the change commits) or the new one (and
  is skipped as `StaleGeneration` or `NotRunning`). It never sees a mix.
- **Lifecycle actions** (section 10.3) take effect through the controller:
  - `Pause` → `pauseTrigger`
  - `Run` → `resumeTrigger`; occurrences during the pause are **not** caught up (section 11.3)
  - `Stop` → unschedule, keeping the fire history
  - `Remove` → delete

  Between the commit and the reconcile, the fire-time guard already blocks fires for the old state.

#### 11.3 Missed occurrences and catch-up identity

ToolGate computes missed occurrences itself, from canonical data only. Quartz's own misfire handling is disabled
(`MISFIRE_INSTRUCTION_DO_NOTHING` on every trigger), so the result never depends on Quartz bookkeeping, which replica
noticed, or whether the catch-up runs from a Quartz fire, the reconciler or a startup scan.

- **Missed window.** For the assignment's current generation, the missed occurrences are the canonical schedule's
  occurrences `t` with `max(lastFiredScheduledTime, generationEffectiveFrom, lastResumedAt) < t ≤ now`, excluding
  any `t < now − startingDeadline`:
  - `lastFiredScheduledTime` comes from the `trigger_schedule_fires` table.
  - `generationEffectiveFrom` is the commit time of the generation. Occurrences of a superseded generation are never
    caught up.
  - `lastResumedAt` is when the assignment last returned to `Running`. Paused or stopped periods are intentional, not
    missed.
- **Policy:**
  - `Skip`: no event; every missed occurrence is recorded `Skipped (Missed)`.
  - `FireOnceNow`: exactly **one** catch-up event that represents the **latest** missed occurrence `t_last`. This
    matches Kubernetes CronJob, which starts the most recent missed schedule.
    - Its `id` is the normal `schedule:<assignmentUid>:<t_last UTC millis>` and its `time` is `t_last`.
    - It adds `tgcatchup: true`, `tgmissedcount: n` and `tgmissedfrom: <t_first>`.
    - Every earlier missed occurrence is recorded `Skipped (Coalesced)`.
- **Determinism and dedupe.** The window is computed inside the fire transaction under the assignment lock. The fire
  history has a unique key `(tenant, assignmentUid, scheduledTime)`. Concurrent catch-up attempts (two replicas, a
  restart while one is running, the reconciler racing a Quartz fire) compute the same `t_last` and the same `id`. One
  inserts; the others hit the unique key and do nothing.
- **Normal fires** after a catch-up continue from the next canonical occurrence after `t_last`.
- **Tests:**
  - restart after 0, 1 and many missed occurrences under both policies, with deadlines partly expired;
  - two replicas and the reconciler catching up at the same moment;
  - a generation change committed between a missed occurrence and the catch-up (old-generation occurrences are not
    caught up);
  - `Pause` during downtime (no catch-up for the paused span);
  - DST gaps and overlaps inside the missed window (occurrences are UTC instants, so a repeated local hour gives two
    distinct ids).
- **Status.** `status.schedule` reports `observedGeneration`, `lastScheduledTime`, `lastFireTime`, `nextFireTime`,
  `missedFires`, `skippedFires` (by reason) and `lastSuccessfulActionTime`. Conditions are `Ready` (reasons `Synced`,
  `ReconcileFailed`) and `Degraded` (reason `MisfiresSkipped`).
- **Limits per tenant:** 500 schedules. The minimum interval is 60 s; operators can lower it to 1 s.
- **Tests:**
  - DST transitions in both directions in several zones, and Control restart across a fire time.
  - Two replicas racing, misfire under each policy, `startingDeadline`, and pause and resume across a fire time.
  - Crash between the assignment commit and the Quartz update; a Quartz job deleted by hand; a stale-generation job
    left in Quartz; an orphan job with another tenant's prefix.
  - A fire racing a `Stop` (the guard must skip).
  - A fire racing a generation change and a `Pause`, both orders, on both databases.

Overlap between consecutive fires is controlled on the **action** (`concurrencyPolicy`, section 14), because the event
itself is instantaneous.

## 12. Event router (transactional outbox)

Every stored event comes from a device or server trigger, a schedule, or an action outcome. It is written in the same
transaction as an outbox row. Router workers in Control claim outbox rows (PostgreSQL `FOR UPDATE SKIP LOCKED`;
single worker on SQLite) and create one **delivery** per matching subscription and per matching action.

| Delivery state | Meaning |
|---|---|
| `Pending` → `InFlight` → `Delivered` | Normal path |
| `RetryWait` | Failed; next attempt after full-jitter backoff (1 s → 5 min) |
| `Held` | Action delivery whose assignment, group or instance is paused (section 14.2, step 0) |
| `Superseded` | Action delivery pinned to an action revision that changed (`onRevisionChange: Supersede`), was revoked, or whose target digest is no longer enabled |
| `DeadLettered` | After 10 attempts or 24 h. Visible through the API and re-drivable by an admin |
| `Skipped` | Condition false, subscription expired, or authorization revoked by the time of delivery |

- Workers run on a dedicated, bounded executor so they never compete with tool-call admission.
- Delivery is at-least-once. Every consumer dedupes by `(source, id)`: subscribers do it client-side, and actions use
  business keys (section 14).
- Authorization is re-evaluated at delivery time, not only at subscription or assignment time.
- Per-tenant fairness: workers claim in round-robin across tenants, so one noisy tenant cannot starve the others.

### 12.1 Retention, cursors and replay

- **Event retention** is per tenant: 7 days by default, configurable downward (and upward only within an operator
  ceiling). Optional shorter retention can be set per event type, for example for classified data.
  - Purge runs in the Control watchdog in bounded batches. It never deletes an event that has non-terminal deliveries
    (`Pending`, `InFlight`, `RetryWait`, `Held`), except when the event is past the hard ceiling (retention + 7 days).
    Then those deliveries become `DeadLettered (Expired)`.
  - Dedupe keys `(tenant, source, id)` are kept as tombstones for the retention window plus 1 day, so a late duplicate
    cannot recreate a purged event.
- **Delivery records** are kept for 30 days after they reach a terminal state, then purged.
  **Dead letters** are kept until re-driven or purged, up to the event retention ceiling.
- **Cursors.**
  - A durable subscription's cursor is deleted with the subscription, and at `expiresAt` plus a 7-day grace for
    renewal.
  - A cursor older than retention resolves to a `gap` on resume (section 13.2), never silently.
  - Ephemeral `watch` sessions have no cursor and no replay.
- **Replay authorization.** Resuming or replaying (`resumeAfter`, admin re-drive) re-checks **current** authorization
  for every event. An event whose source the owner can no longer read is skipped and counted, never delivered because
  it was authorized when it was produced. Replay is limited to the subscription's own sources and to retention.
  Admin re-drive of dead letters requires `trigger.manage` and is audited.

### 12.2 Information-flow policy (one policy for filters, actions, composites and delivery)

Typed expressions prove shape, not confidentiality. One information-flow policy therefore governs every place data
moves: subscription filters, action conditions and arguments, composite steps and results, tool outputs, event
storage, and socket or replay delivery. It is lattice-based label propagation in the style of Denning, checked mostly
at compile time.

**Labels.** Every field carries a classification from the tenant's ordered set: `PUBLIC < INTERNAL < CONFIDENTIAL <
RESTRICTED`, plus optional orthogonal tags such as `personal`.

- **Event data:** `x-toolgate-classification` on fields of the signed event schema. Unlabeled fields are `INTERNAL`.
- **Producer metadata** (`event.producer.*`, `type`, `source`): `INTERNAL`.
- **Tool outputs:** labels on the tool's signed output schema. Unlabeled fields take the descriptor's
  `dataClassification` (default `INTERNAL`). Every output field is **always** joined with the highest label of the
  inputs the tool received, and with any admin-set floor (section 12.3). There is no opt-out in v1.
- **Tool inputs (sinks):** `x-toolgate-max-classification` on input fields says the most sensitive data the tool may
  receive in that field (default `INTERNAL`). It is part of the descriptor, so raising it is a reviewed security delta.

**Clearance.** Each principal has a clearance: the highest label it may read, plus the tags it may read. It comes
from group attributes (Team or Agent Group `dataClearance`, default `INTERNAL`). This replaces the earlier single
`trigger.read.sensitive` permission, which becomes clearance `CONFIDENTIAL` plus `personal`.

**The effective principal** whose clearance applies:

| Context | Effective principal |
|---|---|
| Subscription filter, delivery, replay | Subscription owner |
| Action `when` and arguments | The action's `runAs` Agent. When saving, the author must also hold that clearance |
| Composite steps and result | The composite's caller: a user, an agent, or an action's `runAs` |
| Console and API reads of events, deliveries and action records | The reader |

**Rules:**

1. **Restricted evaluation environment, before evaluation.** An expression is compiled against a type environment
   containing **only** the fields the effective principal may read. At runtime, its activation is built from a view
   with every other field removed.
   - Referencing an unreadable field is a compile error. Dynamic access (`has()`, map indexing, `exists` over keys)
     cannot reach it either, because it isn't in the data the evaluator holds.
   - Dynamic key access is allowed only on map-typed fields whose values share one label.
   - So a filter's true/false outcome can depend only on readable data. *Does this confidential value exist?* cannot be
     asked.
2. **Propagation.** The label of an expression's result is the join of the labels of every field it reads, computed
   statically from the AST and conservative across branches. Composite step results, `let` values and the composite
   result carry joined labels in the same way. A value can never be returned to, delivered to, or stored for a
   principal whose clearance is below its label. Rule 1 guarantees this for expressions; the checks below enforce it
   for stored and delivered data.
3. **Sinks.** A value labelled `L` may be passed into a tool input field only if
   `x-toolgate-max-classification ≥ L`. This is checked at publish and save time from static labels, and again at
   runtime with actual labels. Confidential invoice data therefore cannot be sent to a tool that only accepts
   `INTERNAL` data, even if the `runAs` Agent may read it.
4. **Implicit flows.** Whether an action ran reveals its `when` condition. The action's invocation record and its
   `action.completed` event are labelled with the join of the `when` and argument labels. A reader or subscriber below
   that label gets **no** record or event at all, not a redacted one, because existence is the leak. The same applies
   to a composite step skipped by `when`: its skip status carries the condition's label.
5. **Delivery.** Each socket or replay delivery is the owner's readable view. The filter ran on that same view
   (rule 1). Events with no readable fields are still delivered as envelopes (`id`, `type`, `source`, `time`) if the
   owner may read the source.
6. **No declassification in v1.** No expression, step, action or package can lower a label. A signed output schema is
   evidence of *which package declared* a label, not proof that the output is safe at that label. Package labels can
   therefore only **raise** the effective label (section 12.3). They never lower it below the input join or an admin
   floor.
7. **Re-evaluation on change.** When a principal's clearance drops, or a schema's labels rise in a new version,
   affected subscriptions, actions and composites are re-validated. Those that no longer type-check become
   `Suspended` or `ActionInvalid`.

**Side channels: what is enforced and what is residual.** Correct labels on output fields don't cover data a tool or
trigger leaks through other parts of its response. The plan separates the two:

| Channel | v1 status | How |
|---|---|---|
| Error messages and `isError` details from tools and triggers | **Enforced** | Error text and structured error data are labelled at the output's fallback label (at least the input join) and redacted like data. Under-cleared readers see only the error code |
| CloudEvent `subject` and other envelope attributes set from data | **Enforced** | `subject` may only be derived from a declared event field and carries that field's label. If unreadable, it is removed from the view. Free-form `subject` strings are rejected |
| Resource identifiers in results, links and URLs | **Enforced** where typed | Fields typed as identifiers carry their declared or fallback label like any field. Identifiers embedded in free text are covered only by that text field's label |
| Logs and progress frames from tools and triggers | **Enforced** | Labelled at the tool's fallback label and shown only to principals cleared for it, in addition to the existing secret redaction |
| Audit records | **Enforced** | Audit stores digests and labels, not classified values. Audit readers with lower clearance see the redacted form |
| Result **size**, execution **duration** and **timing**; event **rate** and **count** from a readable source; the size of redacted envelopes | **Residual** (documented, not prevented in v1) | Reveals that something happened and roughly how much, not the classified values |
| Whether a **tool call failed or succeeded** for an action the reader can see | **Residual** for readable actions; **enforced** (hidden) for actions labelled above the reader (rule 4) | |

The conformance corpus (section 12.3) includes tools that try to leak a classified value through error text, `subject`,
identifiers, logs and progress frames. The enforced channels must hold; the residual channels are listed in the
security documentation as known limits.

### 12.3 Enforcement at real tool boundaries (security gate G-IFC)

The policy above is only as good as its enforcement on real, nested, sometimes hostile data. This section pins down
where and how labels are computed, and makes that a release gate.

**Where labels are computed.** Only in Control, which is trusted. They are never computed by an SDK, a tool, a Tool
Host or a device. Labelling happens at four points, which share one labeller implementation:

1. event ingest (against the signed event schema);
2. child and action results arriving through `effects/report` (against the child's signed output schema);
3. building every CEL environment and activation;
4. every read, delivery and replay.

**Labelling concrete JSON:**

- **Schema walk.** The labeller walks the **actual value** alongside the signed schema and produces a label per JSON
  Pointer. It never trusts structure the value merely claims to have.
- **Arrays.** The element schema's label applies to every element. The array's label is the join of its elements'
  labels. With `prefixItems`, each element uses its own subschema.
- **Maps** (`additionalProperties`, `patternProperties`) take the label of the matching subschema. A key matching no
  labelled subschema takes the fallback label.
- **Optional fields.** An absent field has no value to label. An unreadable present field is removed from the view, so
  `has()` cannot tell "absent" from "hidden". Presence is therefore not a channel.
- **`oneOf` / `anyOf` / `if-then-else`:** the join over **all** branches, never only the matched branch, so branch
  selection cannot downgrade.
- **Unlabelled or dynamic structure** (`{}`, untyped values, unlabelled `additionalProperties`): the fallback label,
  which is the maximum of the tool's `dataClassification`, the binding floor, the input join and the **tenant default
  for that integration kind** (below). It is never lower than the input join.
- **Tenant-configurable, fail-closed defaults for unlabelled data.** `INTERNAL` is not assumed for data from outside
  the tenant's reviewed packages. Each tenant sets `defaultClassification` per integration kind, and these are the
  shipped defaults:

  | Integration kind | Shipped default for unlabelled data |
  |---|---|
  | ToolGate SDK packages reviewed and enabled in the tenant | `INTERNAL` |
  | MCP proxy packages (ADR 030) and remote MCP servers | `CONFIDENTIAL` |
  | External integrations reached by routes or TCP destinations (mail, SaaS APIs), including trigger events from them | `CONFIDENTIAL` |
  | Unknown or unclassified source | `RESTRICTED` |

  A tenant can raise any default up to `RESTRICTED` (fail closed), which is recommended for regulated tenants. Lowering
  one below the shipped default is an audited tenant-policy change with maker/checker.
- **Strict validation first.** A tool output that violates its signed output schema is rejected before labelling, and
  the step fails `OutputSchemaViolation`. This covers extra properties where the schema forbids them, type confusion,
  and values out of range. There is no coercion. JSON parsing rejects duplicate keys, normalizes keys to NFC, and limits
  nesting depth (64) and size (section 15.2 limits). Keys such as `__proto__` are plain data.
- **No downgrade by signature.**
  - The effective label of any field is `max(schema label, tool dataClassification, binding floor, join of input
    labels)`.
  - A package author can raise labels but never lower them.
  - **Binding floors** (`dataClassificationFloor` per tool, per route or per MCP server, on the deployment binding) are
    set by tenant admins, not package authors. They let an admin assert that anything from that system is at least
    `CONFIDENTIAL`, whatever the package's schema says.
- **MCP proxy tools and remote MCP servers** (ADR 030): their schemas are third-party and often have no output schema.
  Their entire output is treated as **one value** at the fallback label, with no field-level labels.
- **SDK declaration:** `@Classification(CONFIDENTIAL)` on record components (Java) and
  `Annotated[str, Classification.CONFIDENTIAL]` (Python), emitted as `x-toolgate-classification` in the descriptor. That
  keyword is an amendment to descriptor generation 1, so it is part of gate D0 in M0, and both SDKs need parity tests.

**Conformance fixtures** (`tests/fixtures/ifc/`). The same corpus runs against the Java and Python descriptor
generators, the MCP proxy importer, Control's labeller, the CEL environment builder, and the delivery and read
redactor. It includes:

- arrays of labelled objects; arrays mixing sensitive and non-sensitive values; nested maps; maps with
  `patternProperties`;
- optional and nullable fields; `oneOf` with differently labelled branches;
- unlabelled outputs; `{}` schemas; MCP proxy outputs without schemas;
- outputs whose schema claims a lower label than their inputs or the binding floor;
- malicious tool responses:
  - extra and duplicate keys;
  - deep nesting and oversized arrays;
  - type confusion (string vs object);
  - Unicode-confusable keys;
  - prototype-pollution keys;
  - numbers beyond IEEE precision;
- adversarial **tool chains**, following OWASP guidance for agentic applications: step 1 returns confidential data
  shaped to pass as public input to step 2; an event payload crafted to steer a composite's `when`; a child output
  that injects instructions into text fields.

**Gate G-IFC (release acceptance):**

- every fixture passes at every enforcement point;
- the adversarial suite passes;
- property tests show no evaluation can observe a field above the effective principal's clearance;
- the security owner signs off after an independent review.

**If G-IFC is not met at release time,** v1 ships in **coarse mode**:

- one label per event type and per tool output (the whole payload is one value, at its highest declared label or
  floor);
- field-level labelling stays off;
- every other rule in section 12.2 still applies at that granularity.

Coarse mode is less expressive but just as safe. Field-level mode is switched on when the gate passes.

## 13. Socket subscriptions

### 13.1 `TriggerSubscription`

```yaml
apiVersion: toolgate.olo/v1
kind: TriggerSubscription
metadata:
  name: ap-team-mail-alerts
spec:
  owner: user:alice@example.com          # set from the caller; an agent may own its own subscriptions
  sources:
    assignments: [ap-invoices-mail, ap-daily-report]   # or deviceGroups / pools / eventTypes
  filter: "event.type == 'examples.mail.message.received.v1' && event.data.subject.contains('Invoice')"   # CEL
  includeState: false                    # also deliver STATE events
  expiresAt: 2026-12-31T00:00:00Z        # required; at most 90 days
```

- Creating a subscription requires the new permission `trigger.subscribe` on every source: the Device Group or pool
  of the assignments it names. Schedule assignments are checked against the assignment's own access list.
  `sources` must be explicit; there is no tenant-wide wildcard without `trigger.subscribe.all`.
- The filter is type-checked against the event schemas of the named sources when the subscription is created, and
  rejected if it doesn't compile or exceeds the cost limits (section 15.2).
- **Information flow (section 12.2).** The filter is compiled and evaluated against the owner's readable view only, and
  deliveries carry only that view. A filter like `event.data.amount > 100000` on a `CONFIDENTIAL` field is a compile
  error for an `INTERNAL`-cleared owner, so the pattern of matches cannot leak the value. Action outcome events
  labelled above the owner's clearance are not delivered at all.
- The owner's grants are re-checked at delivery time. A revoked grant marks the subscription `Suspended`, and any open
  socket gets a `revoked` message and closes with code 1008.

### 13.2 WebSocket protocol `toolgate.triggers.v1`

Endpoint: `wss://<tenant host>/api/control/v1/triggers/stream` (Quarkus WebSockets Next). The protocol is described in
AsyncAPI 3.0 under `packages/contracts/asyncapi/`.

1. **Connect.**
   - Authenticate the caller as for any Control API call (ADR 015 token classification; tenant from the host), then
     `POST /api/control/v1/triggers/stream/tickets` returns a single-use ticket valid for 30 s.
   - The client opens the socket with subprotocols `toolgate.triggers.v1, ticket.<ticket>`. Browsers cannot set
     headers on WebSocket, and this keeps tokens out of URLs.
   - **Tickets are credentials:**
     - 256-bit random, stored only as a SHA-256 hash, single use and valid for 30 s;
     - bound to tenant, principal, the request `Origin`, and the authorization epoch at issue;
     - the server echoes only `toolgate.triggers.v1` back as the selected subprotocol, never the ticket.
   - **Keeping tickets out of logs and traces:**
     - Control's HTTP access log, OpenTelemetry HTTP instrumentation and error reports redact
       `Sec-WebSocket-Protocol`, as they already do `Authorization`;
     - the ticket route's response body is excluded from request/response logging;
     - a test scans access logs, traces and audit for ticket values.
2. **Client → server messages:**
   - `subscribe {subscription, resumeAfter?}`: attach to a durable subscription, optionally from a cursor.
   - `watch {filter, sources}`: an ephemeral filter for the console. Same authorization, no stored cursor.
   - `ack {subscription, cursor}`: advances the stored cursor.
   - `credit {n}`: grants the server n more events. The default window is 100.
   - `unsubscribe {subscription}`.
3. **Server → client messages:**
   - `event {subscription, cursor, cloudevent}`
   - `gap {subscription, fromCursor, toCursor, reason: RetentionExpired}`: explicit loss notice when resuming past
     retention.
   - `revoked {subscription, reason}`
   - `error` (RFC 9457 shape)
4. **Liveness and limits.**
   - WebSocket ping every 20 s; the server closes after 2 missed pongs.
   - A consumer with no credit for 60 s is closed with 1013 (*Try Again Later*). Its cursor is kept.
   - Limits: 5 sockets per principal, 1 000 subscriptions per tenant, 64 KiB per message.
5. **Semantics.** At-least-once with ordered delivery per source. The client dedupes by CloudEvent `id`. The stored
   cursor only advances on `ack`.

v1 serves the socket from Control, for the console and for agents and services that can reach Control. Exposing the
same protocol through the Gateway is deferred.

## 14. Actions: event → authorized MCP execution

An assignment of any kind (device trigger, server trigger, schedule) may declare actions. An action turns a matching
event into one governed MCP invocation of a tool or a composite tool (section 15).

```yaml
spec:
  # … trigger or schedule …
  actions:
    - name: ingest-invoice
      enabled: true
      when: "event.data.subject.startsWith('Invoice')"          # CEL; event.producer.* trusted, event.data.* untrusted
      invoke:
        tool: examples.finance/invoice.ingest                   # toolId; or composite: finance.invoice-intake@1.2.0
        arguments:                                              # CEL per field, type-checked against the tool's input schema
          mailbox: "event.subject"
          messageId: "event.data.messageId"
          receivedAt: "event.data.receivedAt"
      runAs: agent:ap-automation                                # an Agent principal of this tenant
      placement: Binding                                        # Binding | OriginatingDevice
      concurrencyPolicy: Forbid                                 # Allow | Forbid | Replace (per action)
      maxConcurrent: 4
      timeout: PT5M
      retry: { maxAttempts: 3 }                                 # full-jitter backoff; see effect rules below
      approval: Policy                                          # Policy | Always (forces ASK)
```

### 14.1 Identity: who the invocation runs as

- **`runAs` is an existing Agent** in the tenant, with a `SERVICE` profile. It gets tool access the normal way: Agent
  Group → Tool Group grants and execution bindings. No new principal type, and the trigger, device or schedule never
  lends its own identity.
- **Separation of duties.** Saving an action requires `trigger.manage` on the assignment's targets **and** the new
  `agent.delegate` permission on the `runAs` Agent. Changing `runAs` is a security-relevant change: it is audited and
  goes through maker/checker where the tenant requires it.
- **The invocation's `RequestContext`:**
  - `credentialKind: AUTOMATION`, the Agent as subject;
  - a delegation `chain` entry `{kind: TRIGGER_ACTION, assignment, action, eventSource, eventId}` (depth-bounded as
    ADR 015 §8);
  - the producer record from section 6.4 for audit.

  Policy can therefore distinguish automated calls, for example require ASK for all `AUTOMATION` calls to a Tool Group.

### 14.2 How the invocation runs

**Action revisions are immutable, and deliveries are pinned to one.** Every saved change to an action creates a new
**action revision**, a canonical (RFC 8785) record with a digest. It contains:

- `when`, `arguments`, `runAs`, `placement`, concurrency, retry and approval settings;
- the resolved **target digest**: `toolId` + `toolDescriptorDigest` + `packageDigest`, or composite name + version
  digest;
- the labels computed under section 12.2.

Revisions are kept while any delivery references them. An admin can mark a revision `Revoked`, for example after
finding it unsafe.

When the router creates an action delivery, it **records `actionRevision` and `targetDigest` on the delivery**. A
historical event is never reinterpreted with a newer definition: an event produced while an action was a read-only
investigation can never execute a newer, write-capable configuration of that action.

The **Action Dispatcher** in Control is a router consumer (section 12). For each delivery it:

0. **Re-checks permission for the pinned revision against the current state.** An event stored before a change may
   still have pending deliveries, so each one is checked at dispatch time:

   | Situation at dispatch | Pending delivery |
   |---|---|
   | Running, action enabled, pinned revision is the current revision | Proceeds with the pinned revision |
   | Assignment, group or instance `Paused` | `Held`. It resumes on `Run`, or is skipped with `ExpiredWhileHeld` after `holdTimeout` (default 24 h) |
   | `Stopped`, removed, action disabled, or action deleted | `Skipped` with the reason (`AssignmentStopped`, `Removed`, `ActionDisabled`) |
   | Action changed since the delivery was created | Governed by the action's `onRevisionChange` (below). The new revision is **never** used for this event |
   | Pinned revision `Revoked`, or its target digest no longer enabled (retired, suspended, kill switch) | `Superseded` with the reason |
   | `runAs` Agent disabled, grants lost, delegation withdrawn, or clearance now below the revision's labels | Denied at admission or by section 12.2; recorded as `Denied` |

   **`onRevisionChange`** is set per action and is itself part of the revision:
   - **`Supersede`** (default): pending deliveries pinned to an older revision are marked `Superseded` and never run.
     An admin can explicitly **re-drive** selected events under the new revision. That is a separately authorized,
     reasoned decision under the **same** operation key, and a **repeat** if the operation already had an attempt
     (section 14.3, *Re-drive and repeat*). A new revision alone never authorizes it.
   - **`ExecuteOriginal`**: pending deliveries run with the **pinned** (older) revision, provided that revision is not
     `Revoked`, its target digest is still enabled, and every current permission check passes for it.

   Invocations already admitted are not affected by these rules. Only `Replace`, an explicit cancel or the kill switch
   cancels them.
1. Evaluates the pinned revision's `when`. Skipped deliveries are recorded.
2. Evaluates the pinned revision's argument expressions (section 12.2 rules apply) and validates the result against
   the pinned target's signed input schema.
3. Submits the invocation through the **same admission path the Gateway uses**: catalog and grant resolution,
   policy, ASK, business key, reservation, and permit (ADR 013 §2, invocation-state v2 T1–T4). Control generates the
   `invocationId` (`origin: TRIGGER`). This is the one new entry point; the states and checks are unchanged.
4. Dispatches through the existing paths: the Control dispatch queue that Tool Hosts pull from
   (`access/dispatches`, `tool-hosts/dispatches/next`) for server tools, the client relay for client tools, and the
   composite orchestrator for composite tools.
5. Records the result through `effects/report` as for any invocation, then emits a correlated CloudEvent
   `io.ololabs.toolgate.trigger.action.completed.v1`. Its `causationid` is the trigger event's `id`, and its data is
   `{action, invocationId, outcome}`. Subscribers can see it.

**Placement:**

- `Binding` uses the tool's execution binding, as for any call.
- `OriginatingDevice` is allowed only for events from device triggers. It places the call on the device that produced
  the event (`event.producer.executorId`), and only if that device is a member of the tool's binding Device Group;
  otherwise the action is denied. Event `data` can never choose a target.

### 14.3 One logical action record per event; effects are only as safe as the target

There are two separate guarantees, and the plan claims only the first unconditionally.

**1. Exactly one logical action record per business operation, independent of event retention.**

The event store dedupes `(tenant, source, id)` only within its retention window (section 12.1). That is **not** the
safeguard for effects. A very late replay, for example a source re-delivering after event tombstones expired, could
otherwise insert the event again and restart a completed financial, infrastructure or administrative operation.
Actions therefore have their own durable identity and ledger:

- **Business-operation key:** `op:<assignmentUid>:<actionName>:<source>:<id>`. It is derived from the stable
  source-level identity (section 5.6), **never** from the event row, its storage ID or its arrival time.
  - The key **never** changes with the action revision. A new action definition does not create a new operation, and
    so does not create a new permission to repeat a business effect (see *Re-drive and repeat* below).
  - A schedule's source identity is `schedule:<assignmentUid>:<scheduledTime>`, so a duplicate Quartz fire maps to the
    same operation. Quartz's own guidance is to design jobs for duplicate execution, and this is how ToolGate does.
- **Three identities below the operation.** Keeping the operation key for history and also sending it to the target
  as the idempotency key would make an intentionally approved repeat impossible: a target that honors idempotency would
  just return the original result. The ledger therefore separates:

  | Identity | Scope | Stable across | Sent to |
  |---|---|---|---|
  | Identity | Scope | Stable across | Implemented by (#230, already on `main`) |
  |---|---|---|---|
  | `operationKey` | The logical business operation, derived from the source event (above). Permanent | Everything | The **business key** of a Control operation (ADR 034). Control binds it in the run-as Agent's authenticated `AGENT` namespace and derives the operation ID itself. **Never** sent to a target as an idempotency key |
  | `effectId` | One **intended effect** of that operation. The first uses effect key `0`; each approved repeat uses the next, `<n>` | Every retry and attempt of that same intended effect | An explicit **per-effect key** (ADR 056). Control hashes tenant, operation, approved route and key into the effect identity; the **broker** injects the downstream idempotency key, and the tool cannot choose or change it. Composite children use the effect key `<n>:<stepId>` |
  | `repeatId` | One explicit, approved decision to perform a **new** intended effect. A UUIDv7 recorded with its approval | — | The trigger repeat-approval record (below). It links the new effect key to its approval and to its exact consent slot (ADR 055) |
  | `attemptId` | One execution attempt of an effect | — | The **attempt journal** (ADR 035): the permit nonce is the attempt ID and fencing token. Each attempt's effect authorization is a generation (ADR 036). Never an idempotency key |

  So a **retry** reuses its effect key (a target with a reviewed guarantee will not apply it twice), and an approved
  **repeat** gets a new effect key (the target sees a new idempotency key and performs the new effect). Both stay
  linked to the same operation.

- **No separate trigger ledger.** Actions use the #230 operation, attempt and effect records as they are on `main`.
  They don't add `trigger_action_operations` or `trigger_action_effects` tables, or any second way to record an
  operation, an effect or an outcome:
  - operations, business keys, windows and retention tombstones: ADR 034 and the S08 business-key policy;
  - attempts and immutable attempt snapshots: ADR 035;
  - effect identities, generations, owners, `SENDING`/`CONSUMED` and ambiguity: ADR 036 and ADR 053;
  - exact consent slots for repeats and high-risk effects: ADR 055;
  - per-effect keys, qualified resend and read-only reconciliation: ADR 056;
  - final operation outcome over every attempt, effect and late evidence: the S10 operation finalizer.
- **What triggers add** (tenant-scoped tables in the fresh schema, section 0):
  - `trigger_action_deliveries`: `{deliveryId, assignmentUid, actionName, actionRevision, targetDigest, source, id,
    firstObservedAt, payloadDigest, operationId}`, unique on `(tenant, assignmentUid, actionName, source, id)`.
    `firstObservedAt` is Control's trusted `receivedAt` of the first ingest of that source id.
  - `trigger_action_repeats`: `{repeatId, operationId, effectKey, approvedBy, checkedBy?, reason, acknowledgedOutcomes,
    consentSlotRef}`, immutable.
  - The delivery row and the first admission are written **in the same transaction**. If the business key is already
    bound, the delivery gets `EXISTING` with the operation's recorded state and outcome, and no new invocation or
    effect is created.
  - They store no payload, only the digest, so long retention is cheap.
- **The #230 records are authoritative.** They answer *has this operation already happened?* even after the event
  payload, the delivery and the invocation result have been purged.
- **Retention follows the target's effect, not the event store:**

  | Target effect (descriptor `effect`, plus the reviewed `effectDomain` tag) | Ledger `retainUntil` |
  |---|---|
  | `READ_ONLY` | 30 days (re-running a read is harmless) |
  | Idempotent side effects | 400 days, and at least the target's own idempotency window when declared |
  | `NON_IDEMPOTENT`, `UNKNOWN`, or `effectDomain` `FINANCIAL`, `INFRASTRUCTURE` or `ADMINISTRATIVE` | **Indefinite**: removed only by an explicit, audited admin purge, which is refused while the source could still deliver that id |

  The effective `retainUntil` is the **maximum** of that value and the source's **redelivery horizon**, meaning how
  long the source may deliver the same id again:
  - `EVENT_LOG`: the source's own retention, declared in the trigger config;
  - `STATE_SNAPSHOT`: **indefinite**, because current state can be re-observed at any time;
  - `NON_REPLAYABLE`: the effect-class value;
  - schedules: until the assignment is removed.

  This value is the business-key retention of the operation, kept with the S08 retention tombstones, and the
  delivery row lives as long. Rows are small (no payload). Capacity planning documents their growth. They are never
  deleted without audit.
- **Trusted time only.** Safety decisions never use the source-supplied CloudEvent `time`, which can be skewed or
  forged. It is display and ordering data only. Ages are computed from Control's trusted `firstObservedAt`, recorded at
  first ingest and kept in the ledger and dedupe index.
- **Late events:**
  - **A previously seen id** (ledger row exists): `EXISTING`. The original attempt's outcome is reported, and nothing
    runs, however late the event is.
  - **A previously unseen id** (no ledger row and no tombstone): it is processed normally. It is genuinely new to
    ToolGate, and its `firstObservedAt` is now.
  - **Optional policy filter:** an action may set `ignoreIfSourceTimeOlderThan`. Because source time is untrusted, this
    can only **skip** work (`Skipped (SourceTimeTooOld)`), never cause it, and it is not a safety mechanism.
- **Re-drive and repeat require their own authorization.** A delivery that was `Superseded`, `Skipped` or `Denied`, or
  an operation that already ran, is never re-run because an action got a new revision. An explicit re-drive is one of
  two kinds:

  | Kind | Intent | When allowed | Authorization | Identity effect |
  |---|---|---|---|---|
  | **Re-drive** | Perform the operation's first effect, which never started | No attempt exists for effect key `0` | `trigger.redrive` on the assignment, a mandatory reason, and admission under the chosen revision as usual | First attempt of effect key `0` |
  | **Retry** | Complete the **same** intended effect | The latest attempt is definitively `FAILED`, or `OUTCOME_UNKNOWN` and read-only reconciliation (ADR 056) witnessed that it was **not** applied. A retry after `OUTCOME_UNKNOWN` without reconciliation is allowed only through ADR 056 qualified resend: a current, independently reviewed target guarantee, inside its window, with current policy, consent, epoch, profile and original deadline rechecked. A declared idempotency alone is not enough | Automatic only under the effect rules of section 14.4; otherwise `trigger.redrive` and a reason | New attempt (ADR 035), **same** effect key |
  | **Repeat** | Perform an **additional**, intentional effect | Every earlier effect is in a known final state (`SUCCEEDED`, `FAILED`, or reconciled). A repeat while an effect is `OUTCOME_UNKNOWN` and unreconciled is refused, so unknowns don't compound | `trigger.repeat` (a separate, high-privilege permission), a mandatory reason, and acknowledgement of the earlier effects' outcomes. For `NON_IDEMPOTENT`, `UNKNOWN` and the financial, infrastructure and administrative domains, maker/checker approval by a second person as well. The new effect takes its own exact consent slot (ADR 055) | New `repeatId` and a **new** effect key (`<n+1>`), linked to the same operation |

  Re-drives, retries and repeats are audited and shown in the console with the full effect and attempt history.
  Repeats are never automated.
- **What this is not.** One logical record per operation is **not** proof of exactly-once external effects. That is
  the subject of point 2.

**2. External effect execution is *not* exactly-once from the dispatcher alone.** The target may commit its effect
while the acknowledgement is lost (executor crash, network partition, timeout). ToolGate then cannot know whether the
effect happened, so the rules are:

- The invocation ends in **`OUTCOME_UNKNOWN`**, never `FAILED`, when the result is ambiguous for a tool whose permit
  says `requiresExecutionLedger` (existing Tool Host behavior).
- **Reconcile before any retry.** An action is re-executed only after reconciliation has established that the effect
  did **not** happen, or that re-executing is safe. Reconciliation is the existing read-only target lookup of ADR 056
  (using the tool's declared `Reconciliation`, ADR 016, `@Reconcile`). It contributes witnessed evidence only and never
  grants new write authority. Without a reconciliation path, `OUTCOME_UNKNOWN` goes to human review (the console and an
  `action.completed` event with `outcome: UNKNOWN`). It is never retried automatically.
- **Stronger guarantees need the target.** "At most one effect" holds only when the target enforces it: the broker
  injects the effect key as an idempotency key the target honors, or the target checks a fencing claim. ToolGate's
  part of that is [#230](https://github.com/olo-labs/olo-toolgate/issues/230). On `main` today, fenced claims cover
  the OLO memory/RAG targets (S17), reviewed target guarantees cover qualified resend (S20), and reviewed generic
  HTTP targets of the `SHARED_SQL` and `SQL_WITH_EXTERNAL_ACTION` fenced classes use shared target claims (S21,
  ADR 057). Native MCP targets are not covered by S21. Until #230's verified slice covers a target or runtime,
  documentation and the console state that action effects on it are **at-least-once with reconciliation**, not
  exactly-once.
- `concurrencyPolicy` (as in Kubernetes CronJob):
  - `Forbid` skips a new event while a previous invocation of the same action is running. The event is recorded as
    `Skipped`, reason `ConcurrencyForbid`.
  - `Replace` cancels the running one through `invocations/{id}/cancel`.
  - `Allow` runs them in parallel up to `maxConcurrent`.

### 14.4 Effect rules (no claims beyond what is implemented)

| Tool's declared effect (descriptor `effect`, ADR 016) | Unattended execution | Retry on failure |
|---|---|---|
| `READ_ONLY` | Allowed | Yes |
| Side effects on a target with a current, independently reviewed guarantee (ADR 056) or a fenced target class (ADR 057), **and** declared reconciliation | Allowed, unless the reviewed route policy requires consent (ADR 055) | Only after a definite failure (`FAILED` with a non-retryable-safe classification excluded), as a retry with the **same** effect key (section 14.3). After `OUTCOME_UNKNOWN`, only through ADR 056 qualified resend |
| `NON_IDEMPOTENT` or `UNKNOWN`, not financial or destructive | ASK approval for **every** invocation, unless the tenant policy `automation.unattendedEffects: allow` is set for that Tool Group. That policy is audited and flagged in the console | Never automatic |
| Reviewed route class `FINANCIAL` or `DESTRUCTIVE` (ADR 053) | **Never unattended.** Every effect needs exact effect consent (ADR 055), whatever the tenant policy says. `automation.unattendedEffects` does not apply, and an unfenced-risk acknowledgement is not consent | Never automatic |
| Any outcome `OUTCOME_UNKNOWN` | — | Never before reconciliation (section 14.3), except ADR 056 qualified resend. Without a reconciliation path: human review only |

For a composite, the effective effect is the most dangerous of its children (section 15.4). Isolation and effect
guarantees are exactly those of normal tool calls on `main` (#230 tracks the rest), and actions add none and claim
none. Every action invocation goes through the same #230 path as an agent's call: reviewed route intent, current
per-effect grant, owner-bound `SENDING` and consent slots (ADR 053–057). Actions have no separate dispatch path.

### 14.4.1 Authorization changes while an operation is running

Re-authorization at dispatch (section 14.2) and per composite step (section 15.4) covers work that **hasn't started**.
For work already running, ToolGate has three different mechanisms with different limits. They must not be confused:

| Mechanism | What it does | Limit |
|---|---|---|
| **Cancellation** (`invocations/{id}/cancel`, existing) | Records cancel intent. The executor stops the tool process at its next check, or kills it | Cooperative and best-effort. An upstream request already sent is not recalled. A cancelled executing invocation ends `OUTCOME_UNKNOWN` (existing rule), never "undone" |
| **Capability revocation** (broker and secret fencing, ADR 013 / ADR 031) | Raises the invocation's authorization epoch. The broker refuses further `ROUTE_CALL`, `TCP_CONNECT` and `SECRET_REDEEM` for that fencing token, and secret handles stop redeeming | Stops **further** brokered effects. It cannot stop a request already in flight, or effects a tool performs with credentials it already holds (`CREDENTIALS_IN_SANDBOX`) |
| **Current per-effect authority** (#230, ADR 053/054) | Control rechecks current grants, epoch and consent at the owner-bound `SENDING` transition, within a send window of at most two seconds | Refuses sends that haven't started. A send already started is not recalled |
| **Target-side fencing and reconciliation** (#230, ADR 057, ADR 056) | A fenced target consumes only its own signed exact-request grant; revocation committed before first consumption refuses it. Read-only reconciliation establishes what actually happened | Only for fenced target classes and reviewed targets. ToolGate makes no claim for targets without it |

**Response to each change:**

| Change | Queued or `Held` | Admitted, not started | Executing | Finished or `OUTCOME_UNKNOWN` |
|---|---|---|---|---|
| `runAs` or caller **loses grants**, Agent disabled, clearance lowered | Denied at dispatch | Permit consumption refuses it (current authority is re-checked at consume, as today) | Capability revocation immediately. Cancellation if the tenant policy `automation.cancelOnRevoke` is set (default **on** for `NON_IDEMPOTENT` and `UNKNOWN` effects). The outcome is recorded; `OUTCOME_UNKNOWN` goes to reconciliation | Unchanged; no retroactive effect |
| ASK **approval withdrawn** | Denied (the approval no longer exists) | If not yet consumed: denied. Consumption is single-use, so a consumed approval cannot be withdrawn; withdrawal then acts as a cancel request | Treated as a cancel request plus capability revocation | Unchanged |
| **Credential revoked** (vault reference, slot binding, binding revision) | The step fails with `CredentialBindingRejected` | The secret handle will not redeem, so the step fails at its first `SECRET_REDEEM` | Brokered credential: refused from the next broker call. `CREDENTIALS_IN_SANDBOX`: the tool keeps the value it already redeemed until it exits. **This is a documented limit**; the target must revoke the credential itself for an immediate stop | Unchanged |
| **Kill switch** on a package or composite | Skipped | Refused | Capability revocation, plus cancellation for every affected invocation | Unchanged |

- **Propagation:** a revocation reaches the broker at its next authorization-epoch refresh. The design target is 5 s,
  but v1 does not measure or claim it (no performance check in v1, section 20.1). The functional test only checks that
  refusal happens before the harness timeout.
- **Audit:** every invocation records which mechanism stopped it. `OUTCOME_UNKNOWN` results from revocation are listed
  for reconciliation.
- **#230 integration:** the plan's claims stop at "no further brokered effects after revocation", plus the target
  fencing #230 has delivered for fenced classes (S21). Push/long-poll kill, inbound mTLS dispatch and measured
  revocation bounds are #230 stage S22 and not yet delivered. Containment of credentials already held and the full
  phase-3 acceptance remain #230 items, and the console says so for each target and runtime.

### 14.5 Guardrails

- **Loop prevention.** Action outcome events cannot trigger actions in v1, and a trigger or schedule is the only
  action source. Events carry `tgcausationdepth`, and anything above 1 is refused.
- **Rate limits:** a token bucket per action (default 60 per minute), and a per-tenant cap on concurrent automation
  invocations (default 50), with excess deliveries in `RetryWait`.
- **Argument expressions.** Expressions that read `event.data` are marked in the dry-run and review output as
  *untrusted inputs*, and the target tool's own validation and confinement still apply.
- **Control.** Actions can be disabled individually (`enabled: false` via `PATCH`). Pausing or stopping an assignment
  stops new events. Deliveries already queued are held or skipped by the dispatch-time re-authorization (section
  14.2, step 0). Admitted invocations are not cancelled unless the admin uses `Replace` or cancels them.

## 15. Composite MCP

A composite tool is a JSON or YAML document that calls zero or more existing tools in sequence. It is published as an
immutable, versioned MCP tool, granted through Tool Groups like any tool, and callable by agents (via the Gateway), by
actions and by schedules.

### 15.1 Definition

```yaml
apiVersion: toolgate.olo/v1
kind: CompositeTool
metadata:
  name: documents.first-text
  version: 1.0.0
spec:
  description: List files in a folder and return the text of the first matching file
  input:                                   # JSON Schema 2020-12; `default` values are applied
    type: object
    required: [directory]
    properties:
      directory: { type: string }
      extension: { type: string, default: ".txt" }
  output:
    type: object
    required: [status]
    properties:
      status: { type: string, enum: [ok, no_files] }
      name:   { type: [string, "null"] }
      text:   { type: [string, "null"] }
  globals:                                 # typed; values come from the composite's deployment binding (ADR 020)
    maxFiles: { type: integer, default: 100, minimum: 1, maximum: 500, overridable: false }
  credentials:                             # slots; bound to vault references in the binding; never readable by expressions
    storage:
      for: { tool: examples.storage/files.list, secret: storage-token }
  steps:
    - id: list
      tool: examples.storage/files.list@^1          # semver range resolved and pinned to a packageDigest at publish
      with:
        path: "input.directory"
        maxResults: "globals.maxFiles"
      credentials: { storage-token: storage }       # child secret ← composite slot
      timeout: PT30S
      onError: fail                                 # fail | continue | { default: <CEL> }
    - id: pick
      let: "steps.list.result.files.filter(f, f.name.endsWith(input.extension))"   # pure computation, no tool call
    - id: read
      when: "size(steps.pick.value) > 0"
      tool: examples.storage/files.read_text@^1
      with:
        # UNTRUSTED: this path comes from another tool's output. CEL only checks it is a string.
        # files.read_text must authorize it against its own approved resource scope (section 15.4.1).
        path: "steps.pick.value[0].path"
      credentials: { storage-token: storage }
  result: >
    size(steps.pick.value) == 0
      ? {"status": "no_files", "name": null, "text": null}
      : {"status": "ok", "name": steps.pick.value[0].name, "text": steps.read.result.text}
```

**Zero steps** is valid. The result is computed from `input`, `globals` and defaults alone. That is useful for a
constant responder, or for mapping an event into a fixed shape that an action passes on.

### 15.2 Expressions, globals, credentials and defaults

- **Expressions are CEL** (`dev.cel` Java library).
  - Every expression is type-checked at validate and publish time against the input schema, globals, each child
    tool's signed input and output schemas, and prior steps.
  - A type error, an unknown field or an over-cost expression is a publish error, not a runtime surprise.
  - No I/O and no non-deterministic functions. `invocation.startedAt` is provided instead of `now()`.
- **CEL limits.** The same limits apply to subscription filters, action conditions and arguments, and composite
  expressions:

  | Limit | Value |
  |---|---|
  | Expression source length | 4 KiB |
  | AST nesting depth | 32 |
  | Static cost estimate (publish time) | ≤ 100 000 cost units per expression. Unbounded comprehensions over unsized lists are rejected unless the list type has a schema `maxItems` |
  | Runtime cost (actual) | ≤ 1 000 000 units per expression, ≤ 5 000 000 per composite invocation; exceeding it fails the step with `ExpressionCostExceeded` |
  | Collection size visible to expressions | Lists ≤ 10 000 elements, strings ≤ 1 MiB, maps ≤ 1 000 keys; larger tool outputs are refused at the step boundary (`StepOutputTooLarge`) before any expression sees them |
  | Comprehension nesting | ≤ 2 (no `map` inside `filter` inside `map`) |
  | Result size | ≤ 1 MiB per expression result |

  Child tool output schemas without `maxItems` or `maxLength` are treated as having the platform ceilings above for
  the static estimate, so the estimate is always bounded.
- **Expressions are not an authorization boundary.** CEL type-checking proves shape, not permission. Every child call
  is authorized and resource-checked by the platform and the child tool (section 15.4.1).
- **Globals:** declared with type and bounds, and resolved with the ADR 020 precedence (platform ceilings > tenant
  policy > binding > composite defaults). A caller's input may override only globals marked `overridable`, within
  their bounds. Anything else is refused.
- **Credentials: binding and provenance.**
  - **Where a credential can come from.** Slots map to a child tool's declared `@Secret`. The **only** place a slot is
    bound to a vault reference is the composite's approved `DeploymentBinding` (ADR 020), which is created or changed
    by an admin with deployment-binding secret permission, reviewed, and versioned.
  - **What cannot influence it.** Composite input, globals, action arguments, event data, subscription filters and
    trigger config **cannot** name, select or alter a vault reference. A caller, event producer, subscriber or
    triggering user therefore has no path to choose which credential a child uses.
  - **What a credential grant is bound to.** Each slot binding is recorded as a tuple: composite name **and immutable
    version digest**, binding ID **and revision**, child `toolId` **and `toolDescriptorDigest`**, child secret name,
    and an **allowed principal set**. The principal set lists the Agents, Agent Groups or Teams whose invocations may
    use it; the default is the composite's grantees, and it is narrowable.
  - **Re-checked on every child step.** Before Control issues the child's secret handle, it verifies:
    - the composite version and binding revision pinned at parent admission still match;
    - the child is the bound `toolId` and digest;
    - the **effective invocation principal** (the composite's caller, or the action's `runAs` Agent with its delegation
      chain) is in the allowed principal set **and** is authorized for the child tool;
    - the child's own binding opts in with `credentialSource: { allowComposite: [<name>@<version digest>] }`, pinned to
      a version, not the bare name;
    - the vault reference is in the tenant and not revoked.

    Any mismatch fails the step with `CredentialBindingRejected`. There is no fallback to the child's own credential
    inside a composite that declared a slot, so a silent identity switch is impossible.
  - Without a slot, the child uses its own binding exactly as a direct call would.
  - Expressions cannot read credentials (the type is opaque). Credentials never appear in inputs, results, events, logs
    or audit, only their slot name and binding revision.
- **Defaults:** come from the input schema's `default`, the globals' `default`, a step's `onError: { default }`, and
  `result` when steps were skipped.
- **Global and credential changes while a composite is running.**
  - **Globals** are resolved once at parent admission. The resolved values and the binding revision are pinned to the
    parent invocation, so every step of one run sees the same values. A new binding revision applies to the next
    invocation, as for in-flight work in ADR 020 §3.
  - **Credential rotation** (a new vault version under the same reference) is **picked up per step**: each child
    redeems its handle at execution, so a rotated secret is used from the next step on. This is intended, because old
    secret versions may be invalidated.
  - **Credential revocation** (reference revoked, slot unbound, binding revision withdrawn, principal removed from the
    allowed set) fails the **next** step with `CredentialBindingRejected`. Earlier steps keep their recorded outcomes.
    A step already executing finishes with whatever its handle was issued for; the handle expires with the step's
    permit.
  - A **kill switch** on the composite or any child package cancels the parent at the next step boundary.

### 15.3 Publishing (no designer; JSON/YAML only)

| Route | Purpose |
|---|---|
| `POST /api/control/v1/composites` | Upload a draft (JSON or YAML). Stored as RFC 8785 canonical JSON with a digest, so equivalent YAML and JSON produce the same digest |
| `POST /composites/{name}/versions/{v}:validate` | Schema, reference, semver resolution, CEL type-check, cost estimate, effect summary, credential opt-in check |
| `POST /composites/{name}/versions/{v}:test` | Dry run with **mocked** child results supplied in the request. No tool is executed |
| `POST /composites/{name}/versions/{v}:publish` | Maker/checker review (auto-approved in Quickstart and dev, as package enable), then immutable publication as `toolId: composite.<name>` with an `mcpName` |
| `GET /composites`, `GET /composites/{name}/versions` | Read |
| `POST /composites/{name}/versions/{v}:deprecate` | Version lifecycle as packages (ADR 014 states up to `RETIRED`) |

A published version pins each child to an exact `packageDigest`. A new child version needs a new composite version,
so a running composite never changes underneath its callers.

### 15.4 Execution

- **Where:** the **Composite Orchestrator** in Control. A call to `composite.<name>` reaches Control's admission like
  any tool call. Control records the parent invocation and runs the steps on a bounded worker pool. A composite
  that is asynchronous, or whose total timeout exceeds the sync deadline, runs as an MCP task (ADR 016).
- **Each step is its own child invocation**, with `parentInvocationId`, its own policy decision, ASK, permit,
  placement and result. It runs **as the composite's caller** (user, agent or action run-as), never as a more
  privileged identity. A composite grants nothing: a caller without permission for a child tool fails at that step.
  This prevents privilege laundering.
- **Durable:** step state lives in the invocation ledger. After a Control crash, completed steps are not re-run, and
  an in-flight step is reconciled through its business key `<parentEffectId>:<stepId>` (section 14.3). A retry of the composite reuses these keys; an approved repeat of it gets a new parent `effectId` and therefore new child keys.
- **Effect summary:** the composite's declared effect is computed as the most dangerous child effect. It is shown at
  review, and drives ASK policy and action rules (section 14.4).
- **Partial failure:** there is no compensation in v1. The result names the failed step, lists completed child
  invocations with their outcomes, and is marked `isError`. Composites with more than one side-effecting step are
  flagged at review.
- **Limits:** 16 steps; one child call per step; sequential only; per-step timeout; total deadline ≤ the task deadline
  (15 min); intermediate data ≤ 4 MiB; result ≤ the tool output limit.
- **Ambiguous child outcomes.** A child that ends in `OUTCOME_UNKNOWN` stops the composite with `OUTCOME_UNKNOWN`. On
  resume, that step is reconciled (section 14.3) before anything else; it is never blindly re-run.
- **Information flow across steps (section 12.2).** The effective principal is the composite's caller; when an action
  starts the composite, that is the action's `runAs` Agent.
  - Every step's `with`, `when` and `let` expressions, and `result`, are compiled against that principal's readable
    view.
  - Each step output is labelled from the child's output schema, always joined with its input labels and the
    binding's floors (section 12.3).
  - Each child input is checked against the child's `x-toolgate-max-classification`: statically at publish, against
    the caller's clearance at admission, and with actual labels before each child call.
  - So an event that starts a composite cannot move confidential data from step 1 into a less-trusted step 2 tool,
    and cannot return it to a caller who couldn't read it directly.
  - Publish-time `:validate` reports the computed label of every step and of the result.
- **Where orchestration runs, and what Control keeps.** v1 runs the orchestrator workers inside Control for
  simplicity. The design keeps them stateless: all state is in the ledger, and each step is an admission plus a wait.
  They can therefore move to a separate worker service later, while Control remains the only owner of policy
  decisions and durable invocation state.

#### 15.4.1 Resource-level authorization of child arguments

Values passed between steps are **untrusted data**, even when they come from another governed tool. In the example,
`steps.pick.value[0].path` could be `../../other-tenant/secret.txt` or a symlink planted by whoever controls the
folder. CEL type-checking only proves it is a string. Protection comes from the child and the platform, at the moment
of the child call:

- **The child tool authorizes the resource itself, against its approved scope,** exactly as for a direct call by an
  agent:
  - Device files tools: the confinement rules of ADR 027 §3, which are portable relative names only, no `..`, absolute
    paths, hidden or device names, no-follow traversal, single-link regular files, and reparse-point checks.
  - Storage or API tools: the service profile's approved routes and resource templates, plus the target service's own
    ownership checks (as in `olo-service-permits.md`).
- **Approved resource scope is configuration, not input.** It comes from the child's deployment binding and service
  profile, for example an approved root or bucket. A composite cannot widen it, and neither can a caller, an event or a
  previous step.
- **Tenant isolation does not depend on the path.** The child runs on an executor of the invocation's tenant, with that
  tenant's files area or service credentials. A path cannot name another tenant's storage.
- **Platform checks per child call:** input-schema validation, policy and grants for the effective principal, and
  argument-digest binding in the permit. The path that is authorized is exactly the path the tool receives.
- **Publish-time flagging:** `:validate` marks every argument that flows from a previous step's output or from event
  data as *untrusted*. It warns when the receiving child declares no resource confinement: no `@DeviceFileAccess`
  confinement, and no route resource template.
- **Tests** for the sample composite: traversal (`../`), absolute paths, a symlink and a hard link in the listing, a
  Windows reparse point, a path naming another tenant's area, and an oversized listing. Each must be refused by
  `files.read_text`, not by the composite.

### 15.5 Gateway change

When admission identifies a composite, the Gateway handles the call like a relayed task: it returns the task, or waits
within the sync deadline, and reads the result from Control. Agents see one MCP tool with the composite's input and
output schemas.

## 16. Admin console: raw Triggers page (v1)

A `#triggers` route in the embedded console, with no designed UI:

- **Upload:** a file picker that posts to the package upload route, then shows the raw review/enable result and links
  to the existing review screen.
- **Definitions:** raw JSON.
- **Assignments:** a YAML/JSON editor with *Dry run* and *Apply*; raw `status`.
- **Actions:** a YAML/JSON editor for `:action` requests, with *Dry run* (lists the instances that would change) and
  *Apply*.
- **Instances and events:** raw JSON with filters.

- **Schedules, subscriptions and composites:** the same raw editors (YAML/JSON with dry run, validate, test and
  apply). A **Live** panel opens an ephemeral `watch` socket and prints events as they arrive.
Fields above the viewer's clearance (section 12.2) are removed from every view, and records labelled above it are not
shown at all. Nothing on the page computes a security decision.
viewer has `trigger.read.sensitive`. Nothing on the page computes a security decision.

## 17. Multi-tenant boundaries

1. **Data:** every new table has `tenant_id` as the leading key column: `trigger_definitions`,
   `trigger_assignments`, `trigger_assignment_revisions`, `trigger_overrides`, `trigger_instances`, `trigger_leases`,
   `trigger_checkpoints`, `trigger_executor_snapshots` and `trigger_events`. The tenant always comes from the request
   context. PostgreSQL row-level security is added as defence in depth on the event and checkpoint tables, which hold
   source data.
2. **Packages:** definitions exist only in the tenant where the package version is enabled. The same digest in two
   tenants is fully independent.
3. **Executors:** Tool Hosts and pools belong to one tenant (workload SAN `…/tool-host/<tenantId>/<poolId>/<hostId>`),
   and devices to one tenant. Placement, leases and desired documents never cross tenants.
4. **Allocation and actions:** targets and selectors resolve only within the caller's tenant. Unknown and foreign IDs
   give the same `404`.
5. **Ingress and leases:** events, checkpoints, secret redemption, tunnels and watches are accepted only with the
   current lease of an executor in the same tenant.
6. **Secrets:** resolved from the tenant vault per lease epoch. They never appear in desired documents, snapshots,
   checkpoints, events, logs or audit.
7. **Fair use, per tenant:** max assignments (200), instances per device (32) and per Tool Host (256), event ingest
   (token bucket, default 50 000 per hour), retained events (100 000), retention (7 days, configurable downward). One
   tenant's limits cannot consume another's capacity.
8. **Quickstart (SQLite)** is single-tenant. Shared hosting requires PostgreSQL.
9. **Schedules:** Quartz job keys start with the tenant ID, and Quartz stores no tenant data. Each fire job loads its
   assignment within that tenant only.
10. **Subscriptions and sockets:** the tenant comes from the host and the ticket. A socket only sees subscriptions of
    its own principal in that tenant, and subscription sources must be in the same tenant.
11. **Actions and composites:** `runAs` Agents, target tools, child tools, bindings and credentials must all belong to
    the same tenant. Resolution never looks outside it.
12. **Router fairness:** per-tenant round-robin claiming and per-tenant automation concurrency caps.
13. **Tests:** a cross-tenant negative test for every route, action selector, ingress path, lease operation, secret
    redemption, placement decision, socket ticket, subscription source, action target and composite child.

## 18. Delivery semantics summary (for documentation)

> Triggers and schedules deliver events to ToolGate **at least once**. ToolGate stores each event **once** per
> `(source, id)` within the retention window. Events from one trigger instance are ordered.
>
> **Completeness depends on the source class:**
>
> - `EVENT_LOG` sources recover every event the source still retains.
> - `STATE_SNAPSHOT` sources recover current state, not intermediate changes.
> - `NON_REPLAYABLE` sources are best effort, and their detectable losses are counted.
>
> A rejected event never silently advances a checkpoint. Either the trigger halts, or an approved policy records the
> loss.
>
> Socket subscribers receive events at least once, in order per source, and can resume within retention, subject to
> current authorization. Each action has **one logical action record per business operation**, kept in a durable operation ledger that outlives event retention.
> External effects are **at-least-once with reconciliation**: an ambiguous outcome is `OUTCOME_UNKNOWN` and is
> reconciled before any retry. Exactly-once effects need target-side idempotency or fencing (#230).

## 19. Security notes

- **Least privilege:**
  - Client triggers get read-only files access, and `nativeMount` only when reviewed.
  - Server triggers get exactly the reviewed routes, destinations and secrets.
  - Push listeners reach the network only through declared destinations.
  - A write-capable route or `CREDENTIALS_IN_SANDBOX` is flagged in the security delta.
- `data` is untrusted. It is stored, size-bounded, schema-checked and rendered as text, and nothing acts on it in v1.
- Sample triggers emit metadata only, with no message bodies or attachments.
- Long-running credentials:
  - Brokered credentials are re-fetched per lease epoch.
  - Tunnels opened by a push trigger close when the lease ends or is revoked.
  - A maximum connection lifetime (default 24 h) forces periodic re-authentication.
- Allocations and actions are audited administrative changes. Enterprise tenants can require maker/checker.
- The kill switch (ADR 017) revokes every lease of a suspended package.
- **Automation:**
  - Actions run as a named Agent with explicit grants and a delegation chain. Binding an Agent requires
    `agent.delegate`.
  - Event data can fill arguments but can never pick the identity, tool or target.
  - Non-idempotent effects need ASK unless the tenant explicitly opts in.
- **Composites:**
  - Each child call is authorized as the effective principal, and each child authorizes its own resources (section
    15.4.1).
  - Credentials come only from the approved binding. Each is bound to the composite version digest, binding revision,
    child identity and allowed principals, and re-checked per step (section 15.2).
  - Expressions are type-checked, cost-bounded and side-effect free, and are not an authorization boundary.
- **Sockets:**
  - Tickets are hashed, single-use, origin- and principal-bound credentials, redacted from access logs and traces.
  - Authorization re-checked at delivery and replay; immediate revoke on grant loss.
  - Classified fields are removed before matching and delivery, and filters can't reference fields the owner can't
    read.
  - Per-principal connection limits.
- **Leases are not execution authority.** No lease, including the 24 h client lease, authorizes offline actions or
  effects (section 7).
- **Information flow (section 12.2):** one label-and-clearance policy covers filters, actions, composites, tool
  outputs and delivery. Expressions see only readable fields; results carry joined labels; tool inputs have
  classification ceilings; implicit flows (whether an action ran) are hidden from under-cleared readers. There is no
  declassification in v1.
- **Historical events are never reinterpreted.** Action deliveries are pinned to an immutable action revision and
  target digest (section 14.2).

## 20. Observability

**Metrics** (OpenTelemetry, exported through the existing pipeline; low-cardinality labels: tenant, trigger, executor
kind, phase):

- `toolgate.trigger.events.emitted`, `.rejected`, `.dropped`, `.deduplicated` (counters)
- `toolgate.trigger.outbox.depth`, `.oldest_age` (gauges)
- `toolgate.trigger.ingest.latency` (histogram: event `time` → Control `receivedAt`)
- `toolgate.trigger.lease.renewals`, `.failovers`, `.expirations`
- `toolgate.trigger.restarts`, `toolgate.trigger.reconcile.duration`
- `toolgate.trigger.instances` by phase (gauge)
- `toolgate.trigger.schedule.fires`, `.misfires`, `.lag` (actual vs scheduled fire time)
- `toolgate.trigger.router.backlog`, `.deliveries` by outcome, `.dead_letters`
- `toolgate.trigger.socket.sessions`, `.events_sent`, `.slow_consumer_closes`
- `toolgate.trigger.action.invocations` by outcome, `.skipped` by reason, `.approval_waits`
- `toolgate.composite.invocations`, `.step.duration`, `.step.failures`, `.credential_rejections`, `.expression_cost_exceeded`
- `toolgate.trigger.schedule.repairs`, `.skipped_fires` by reason, `.reconcile_lag` (generation committed → observed)
- `toolgate.trigger.action.outcome_unknown`, `.reconciliations` by result, `.held`
- `toolgate.trigger.checkpoint.lag` (emitted seq − LOCAL and ACCEPTED watermarks)

**Traces and correlation:**

- Each event carries `traceparent`. Its span links to the trigger instance and lease.
- ADR 031 correlation IDs are used in audit and logs.

**Alert suggestions** (documented, not built in v1): outbox age over 10 minutes, any `CrashLoop`, failover rate,
tenant over 80% of quota.

### 20.1 Capacity, resource isolation and later performance qualification

> **v1 has no performance check.** No load test, throughput target, latency target or capacity figure is a v1 release
> gate, acceptance criterion or documented claim. v1 documentation states that capacity is **unqualified**. The v1
> release gates are G-STATE, G-IFC and the M12 functional scenarios (section 21). Everything under *Qualification
> targets* below is for a **later** qualification release, and is not evidence of v1 capacity.

v1 runs the event router, socket handling, action dispatcher, schedule reconciler and composite workers inside
Control. A bounded worker pool alone doesn't isolate them, because they would share database connections,
transactions, CPU and heap with tool-call admission. v1 therefore **implements the bulkheads below as a design
requirement**. They are verified functionally (each pool, cap and shed rule has a unit or integration test), not by
load testing.

**Bulkheads:**

| Resource | Isolation |
|---|---|
| Database connections | Separate named pools (Agroal datasources): `admission` (existing tool-call path), `ingest` (trigger sync and events), `automation` (router, actions, composites, reconciler) and `realtime` (subscription cursors). Each has a hard maximum. Together they stay within the database's connection budget, and `admission` keeps a reserved minimum it never lends |
| Transactions | Automation transactions are short (one delivery or one step), with a statement timeout (5 s) and a lock timeout (2 s). There are no long transactions that would hold locks needed by admission |
| CPU and threads | Each subsystem has its own executor with a concurrency cap and bounded queue: ingest, router, actions, composites, reconciler, CEL evaluation. CEL runs only on its own pool, with the runtime cost limits of section 15.2. Under CPU saturation, work is shed in a fixed priority order: admission, then ingest, then schedules, then actions and composites, then router fan-out to sockets |
| Memory | Per WebSocket session: send buffer ≤ 1 MiB and ≤ 100 unacknowledged events (the credit window). Global socket buffers ≤ 256 MiB per Control replica. Above that, the slowest consumers are closed with 1013 and keep their cursors. Router batches ≤ 500 rows. CEL activations are capped by the collection limits |
| Tenants | Per-tenant concurrency caps in every pool (ingest, router, actions, composites), round-robin claiming, and per-tenant token buckets. One tenant can never hold more than its share of any pool while others are waiting |

**Qualification targets (post-v1, not checked in v1).** These are **qualification targets, not demonstrated
capacity**. They are a starting hypothesis for the first qualification run after v1, and are revised against the
measured baseline. Some are deliberately aggressive (1 000 events/s sustained, 5 000 sockets, a 1 000 000-event backlog
in 15 minutes) and may not hold on the reference profile.

A run is comparable to another only if its **workload fixture** is identical. The fixture is versioned and recorded
with every result:

| Fixture parameter | Example value |
|---|---|
| Hardware and deployment | 2 Control replicas (4 vCPU, 8 GiB), PostgreSQL (4 vCPU, 16 GiB, SSD) |
| Database configuration | `shared_buffers`, `max_connections`, pool sizes per datasource, `synchronous_commit`, autovacuum settings |
| Event payload | Size distribution (e.g. p50 1 KiB, p99 16 KiB), schema depth, number of labelled fields |
| Classification complexity | Fields per label level, arrays and maps per payload, share of events needing redaction |
| Fan-out | Subscriptions per event (e.g. 3), actions per event (e.g. 0.2), share of composites and steps per composite |
| Subscription filters | Total filters, filters per source, CEL cost distribution |
| Tenants | Tenant count and traffic skew |
| Duration | 30-minute steady state plus burst phase |

| Metric | Target |
|---|---|
| Schedule lateness (actual − scheduled fire time) | p99 ≤ 2 s at 500 schedules firing every minute, including during reconciler full scans |
| Event ingest latency (executor send → `STORED`) | p95 ≤ 300 ms, p99 ≤ 1 s at 1 000 events/s sustained; 5 000 events/s burst for 60 s without errors other than `retryLater` |
| Action dispatch latency (`STORED` → admission submitted) | p95 ≤ 1 s, p99 ≤ 3 s at 200 actions/s |
| Socket delivery latency (`STORED` → frame sent) | p95 ≤ 500 ms with 5 000 connected sockets |
| Tool-call admission isolation | Existing tool-call admission p99 regresses ≤ 10% while all automation subsystems are saturated |
| Per-tenant fairness | A tenant at 10× its quota raises other tenants' ingest and dispatch p99 by ≤ 20% |
| Backlog recovery | A 1 000 000-event router backlog drains in ≤ 15 min with heap flat (no growth beyond steady state + 10%), while live p99 targets degrade by at most 2× |
| Slow consumers | 10% of 5 000 sockets stop reading. Heap growth ≤ 256 MiB, other sockets' p95 unchanged within 20%, and slow consumers closed within 60 s |
| Revocation propagation | Grant loss → broker refusal ≤ 5 s p99 (section 14.4.1) |
| Composite orchestration | 100 concurrent composites of 5 steps, with orchestration overhead (excluding child execution) ≤ 50 ms p95 per step |

**When a later qualification run misses a target,** the remedy is chosen from **bottleneck evidence**: profiles, lock
and wait analysis, and pool saturation. It is not assumed. The options, in order of cost:

1. tuning (pool sizes, batch sizes, indexes, database settings);
2. code-level fixes;
3. moving the subsystem to a separate worker deployment (the stateless design in section 15.4 keeps this possible).

Separation is chosen only when the evidence shows contention with admission or shared Control resources that tuning
cannot remove. It is not a default.

### 20.2 Bounded resource-safety tests (functional; part of the v1 M12 gate)

These are **not** performance tests. They have no events-per-second, latency or throughput targets. Each one drives a
resource to its configured limit and checks three things: the limit holds, the admission path still works, and memory
stays within the configured bounds. They run in CI on Quickstart, and on the PostgreSQL profile where pools differ.

Configured caps are set to **small** test values (for example a 50-event outbox, a 2-connection automation pool, a
256 KiB socket buffer), so limits are reached quickly. The checks are functional:

| Test | Drive to the limit | Pass criteria |
|---|---|---|
| Outbox full | Emit into a client and a Tool Host outbox until full while Control is unreachable | `emit()` reports `RETRYABLE_REJECTION`; the outbox accounting counters never exceed their configured bounds; the process is not OOM-killed; no checkpoint advances |
| Worker pool exhausted | Saturate the router, action, composite and CEL pools with blocking work (test-only stub tools) | Queues stay at their configured bounds and excess goes to `RetryWait` or is rejected; a normal tool call through the Gateway **still completes** within the harness timeout; no thread waits longer than the lock timeout (thread-dump watchdog) |
| Automation DB pool exhausted | Hold every `automation` connection | The `admission` pool keeps its reserved minimum (checked through pool metrics), and a tool call still completes. Ingest and automation work queues instead of borrowing admission connections |
| Slow subscribers | Open sockets that never read, up to the configured socket count | Per-session buffers stop at their cap, the global socket-buffer cap is never exceeded, slow sessions close with 1013 and keep their cursors, and other sessions keep receiving events |
| Event flood | Send events beyond the per-instance and per-tenant token buckets | `retryLater` / `429`, with no unbounded queue in Control (router backlog rows are bounded by the outbox, not by memory) |
| Expression and output bombs | A CEL filter at the cost limit, a tool output at the size limits, a deep-nesting payload | Rejected with the documented error, and no evaluation or allocation beyond the configured caps |
| Snapshot and check-in limits | Worst-case check-in (section 8.5) and a 128 KiB checkpoint | The check-in stays within budget through paging; the checkpoint moves through the chunked transfer; nothing is truncated |
| Deadlock watchdog | Every test above | A watchdog fails the test if any thread is blocked longer than its configured timeout, or if the admission health check fails at any point |

**Memory assertions: two levels, so the tests are deterministic.**

1. **Primary invariant: explicit accounting.** Every bounded structure keeps an exact counter of what it holds,
   maintained on enqueue and dequeue and exposed to tests (and as metrics). The tests assert **every** counter stays at
   or below its configured cap at every sampling point and at the end. These checks don't depend on garbage collection
   or runtime overhead, so they pass or fail deterministically. The structures are:
   - executor outboxes (records and bytes);
   - per-session and global socket buffers (bytes and events);
   - router and worker queues (items);
   - snapshot page buffers;
   - checkpoint upload buffers;
   - CEL activations (estimated bytes, from the collection limits);
   - in-flight tool outputs (bytes).
2. **Secondary check: a process-memory ceiling.** A coarse safety net, not a precise measure. The process runs with a
   fixed container memory limit and fixed heap settings (`-Xmx` for Control; a cgroup limit for executors), and the
   test passes if:
   - the process is **not** OOM-killed;
   - no `OutOfMemoryError` occurs;
   - the admission health check never fails.

   It never asserts exact heap or RSS numbers. Explicit `System.gc()` and heap-delta comparisons are not used, so
   unrelated garbage collection timing cannot change the result.

None of these assertions compare against throughput.

## 21. Delivery plan

The milestones below define **scope**. The **execution order**, one module at a time with each module's effort
percentage and owned scenarios, is in section 24.

Each milestone ends with automated tests on both databases where relevant. Milestones are numbered in dependency
order, and **M12 is the release gate**.

| Milestone | Depends on |
|---|---|
| M0 Contracts and ADR | — |
| M1 SDK POLL, M2 SDK PUSH | M0 |
| M3 Control core | M0 |
| M4 Supervisor crate | M0 |
| M5 Executors | M2, M4 (+ Tool Host TCP tunnels for the IMAP sample) |
| M6 Ingress and queries | M3, M4 |
| M7 Raw page | M3, M6 |
| M8 Schedules | M3 |
| M9 Event router and sockets | M6, M8 |
| M10 Actions | M9 |
| M11 Composite MCP | M3, M10 (composites as action targets) |
| M12 End-to-end release gate | all |

**Release gates.** All three must pass on the release commit; none can be waived:

| Gate | Defined in | Evidence |
|---|---|---|
| **G-STATE** | Section 6.6.1 | TLC model check of `trigger_delivery.tla`, the stateful property tests, and the nightly deterministic simulation with no open failing seeds |
| **G-IFC** | Section 12.3 | Conformance corpus at every enforcement point, the adversarial tool-chain suite, and security owner sign-off. Otherwise ship in coarse mode |
| **M12 scenarios** | Section 21 | All end-to-end scenarios pass in CI against Quickstart, and the PostgreSQL profile for the multi-tenant ones |

**No performance check in v1.** There is no performance or load gate, and no performance acceptance criterion, in v1.
Timing bounds that appear in functional scenarios (such as "within 5 s" or "within 60 s") are functional timeouts in
the test harness, not latency or throughput claims. Performance qualification (section 20.1) is planned for a later
release.

The **bounded resource-safety tests** (section 20.2) **are** part of the M12 gate. They are functional safety tests,
not performance benchmarks: they check that limits hold and nothing deadlocks or grows without bound, with no rate,
latency or throughput target.

### M0 — Contracts and ADR (gate D0)
- ADR 058 covering everything in sections 5–10. Amend the SDK authoring spec and runtime modes, ADR 013 (lease-bound
  broker, tunnels and secrets), and ADR 027 (trigger frames, `FILES_WATCH`).
- `v2/trigger.schema.json` and protocol frames; CloudEvents JSON schema reference; fixtures; proposed OpenAPI.
- Decide how Tool Hosts verify the desired document.
- `docs/specs/trigger_delivery.tla` (section 6.6.1), model-checked before M4 starts.
- Descriptor amendment for `x-toolgate-classification`, `x-toolgate-max-classification`, `dataClassification` and
  `effectDomain`. Verify that the Java and Python descriptor generators and the MCP proxy importer can carry these
  keywords on nested, array and map schemas, and record the gaps.
- First version of the IFC conformance corpus (section 12.3), so later milestones are built against it.

### M1 — SDK, POLL mode
- Java and Python annotations, `PollContext`, `PollResult`, checkpoints, the runtime loop, processor rules,
  `toolgate check` and `toolgate pack` support, and harness support.
- Sample: `examples.mail-watch` (REST, brokered OAuth).

### M2 — SDK, PUSH mode
- `PushTrigger`, `PushContext` (`tcp().socketFactory`, `files().watch`, `runInBackground`), `TriggerEmitter` with async
  durability acks, event `position`, the contiguous checkpoint barrier at `LOCAL` and `ACCEPTED` levels,
  `sourceReplay`, `reconcile`, pause and resume hooks, and heartbeats.
- Property tests for the barrier: randomized completion order, rejections and crashes never commit past an unresolved
  emit.
- Harness: inject watch events, network faults and slow acks.
- Samples: `examples.mail-idle` (IMAP IDLE via Jakarta Mail, `CREDENTIALS_IN_SANDBOX`), `examples.inbox-watch`
  (executor-native watch), and a `nativeMount` variant. The Python twins implement **the same semantics** as the Java
  samples: hints plus reconcile, exact version keys, `SnapshotState`, and the IMAP identity with `UIDVALIDITY`.
- **Shared SDK trigger conformance fixtures** (`tool-sdk/conformance/triggers/`), run identically against the Java and
  Python runtimes and both samples (ADR 028 parity). Each one asserts emitted ids, positions, checkpoints and
  conditions:
  - **Incomplete listing:** a listing that fails or truncates part-way prunes nothing, emits nothing spurious, and
    retries.
  - **Source identity changes:** `UIDVALIDITY` changes; a file is replaced (new file ID); renamed (same version); or
    rewritten (new size or mtime); a source epoch changes position order.
  - **Snapshot overflow:** capacity reached by count, and by encoded size, leads to `SnapshotCapacityExceeded`, no
    checkpoint advance, and rescan mode.
  - **Checkpoint after process restart:** kill the process after emits but before the barrier advances. The restart
    resumes from the last committed checkpoint, re-emits with the same ids and new `emitSeq`, and the result equals an
    uninterrupted run.
  - **Callback disorder:** out-of-order, duplicated and dropped native callbacks produce the same result as in-order
    ones.
  - **Non-monotonic position** leads to `NonMonotonicPosition` and `HALT`.

### M3 — Control: definitions, assignments, actions, leases, desired state
- Upload verification and the schema-compatibility check.
- The tables above in the fresh initial schema (section 0), with the spec/status model.
- Assignments CRUD with ETag, idempotency, merge patch and dry run; `:action` and `:rollback`; override resolution.
- Rolling update controller, server placement and failover, lease store, signed desired document.
- Lease-bound secret delivery, permissions, audit and quotas.

### M4 — Shared supervisor crate
- Lease enforcement, frames, outbox with fsync and watermarks, CloudEvents wrapping, checkpoints, health, backoff,
  resync timer, `TriggerSync` building and ack handling.
- The sequence namespaces of section 6.2.1 (`emitSeq` ↔ `outboxSeq` mapping), the rejection classes, loss records,
  `onTerminalRejection` (`HALT` / `SKIP_AND_RECORD`), and handling of `dispositions` and `retryLater` in the ack.
- Property and fault-injection tests: crash at every write point, lost and duplicated acks, clock skew, disk full.

### M5 — Executors
- **Tool Host:** long-running sandbox per instance, lease-bound broker, checkpoint upload, `triggers.v1`.
- **Dependency:** `TCP_CONNECT` tunnels for push listeners (the IMAP IDLE sample) need the Tool Host tunnel support
  from ADR 013, which the Tool Host README lists as not yet built. Until it lands, server triggers ship with POLL only
  (`examples.mail-watch`), and the IMAP PUSH scenarios in M12 are skipped. Client PUSH (`FILES_WATCH`) has no such
  dependency.
- **Endpoint client:** `FILES_WATCH` on inotify, `ReadDirectoryChangesW` and FSEvents, with confinement and overflow
  handling (an overflow forces `RECONCILE`); `nativeMount` on capable Linux hosts; `triggers.v1` in the presence ping.

### M6 — Control: ingress and queries
- Certificate-bound ingress, lease and epoch checks, `(source, id)` dedupe, re-validation, token buckets and `429`.
- Checkpoint persistence after ack, derived conditions, instance and event queries, retention purge, OpenTelemetry
  metrics.
- The check-in budget and paging (section 8.5), with the worst-case serialization contract test; the chunked checkpoint
  transfer with an atomic digest, version and body commit (section 8.6).

### M7 — Raw Triggers page

### M8 — Schedules (Quartz)
- Add `quarkus-quartz`, with a clustered JDBC store whose tables are in the fresh PostgreSQL schema, and a RAM store plus
  fire-history recovery on SQLite.
- The schedule source on `TriggerAssignment`; the configuration outbox and schedule reconciliation controller
  (event-driven plus full reconciliation every 5 minutes); the fire-time guard; the fire job writing the event, outbox
  and fire-history rows in one transaction; misfire and `startingDeadline` handling; lifecycle mapping;
  `status.schedule.observedGeneration`.

### M9 — Event router and socket subscriptions
- Outbox, router workers, deliveries (including `Held` and `Superseded`), dead letters and re-drive.
- Retention, tombstones, cursor cleanup, and replay re-authorization.
- Information-flow core (section 12.2): labels in event and tool schemas, `dataClearance` on Teams and Agent Groups,
  the restricted CEL environment and redacted activation, static label propagation, and sink checks. It is shared by
  M9–M11.
- `TriggerSubscription` CRUD with CEL filters (`dev.cel`) compiled against the owner's readable view; CEL limits.
- Hashed single-use ticket endpoint with log and trace redaction; WebSocket `toolgate.triggers.v1`
  (`quarkus-websockets-next`) with credit flow control, resume and revocation. AsyncAPI document.

### M10 — Actions
- Action spec, validation and dry run (CEL type-check against event and tool schemas).
- `agent.delegate`; the Control-originated admission entry point (`origin: TRIGGER`, `credentialKind: AUTOMATION`).
- Immutable action revisions with target digests. Deliveries pinned at creation; `onRevisionChange`
  (`Supersede` / `ExecuteOriginal`); revision revocation; explicit re-drive under a new revision.
- Dispatch-time permission re-check (`Held`, `Skipped`, `Superseded`, `Denied`).
- Information-flow checks for `when` and arguments (runAs clearance, sink limits, outcome-event labels).
- One logical record per event as a #230 business-key operation (ADR 034), with the thin `trigger_action_deliveries`
  and `trigger_action_repeats` tables only; effects through ADR 053–057 with explicit effect keys and consent slots;
  `OUTCOME_UNKNOWN` handling through ADR 056 reconciliation and human review; concurrency policies; effect rules; rate
  limits; outcome events; placement `OriginatingDevice`.

### M11 — Composite MCP
- Draft, validate, test, publish and deprecate routes; canonical digest; semver pinning.
- CEL type-checking and limits; untrusted-argument flagging; per-step and result labels with sink checks (section
  12.2).
- Globals pinned at admission; credential slot bindings bound to the version digest, binding revision, child identity
  and principal set, re-checked per step; rotation and revocation behavior.
- Composite orchestrator with durable steps, crash resume and `OUTCOME_UNKNOWN` reconciliation; task mode; effect
  summary; the Gateway admission change.

### M12 — End-to-end scenarios (CI against Quickstart, release gate)

**Server, PUSH (IMAP IDLE against a test IMAP server in compose) and POLL (REST stub):**
1. Upload, review (the delta shows destinations, secrets and mode), enable, and allocate to a two-host pool. The
   instance is `Running` on one host at epoch 1.
2. A new message produces one event within 5 s (push) or one interval (poll). A non-matching message produces none.
3. Kill the holding host. After failover, the instance runs at epoch 2 on the other host, resumes from the checkpoint
   and runs `reconcile()`. Messages that arrived during failover are reported, and no message is stored twice.
4. Partition the old host instead of killing it. It stops at `renewDeadline`, and its late events are rejected.
5. `Pause`: no events, lease kept. `Run`: resumes. `Stop`: lease released and the tunnel closed. Kill switch: all
   leases revoked within 60 s.
6. Upgrade `packageVersion` (compatible event schema): the instance restarts from the checkpoint. An incompatible
   event schema is refused at upload.

**Client, PUSH (executor-native watch) and POLL:**
7. A dropped file produces one event in under 5 s (push). Force a watch overflow: `reconcile()` recovers the missing
   files, with no duplicates.
8. Fill the outbox while offline with the (`STATE_SNAPSHOT`) file watcher: `Degraded=Backpressure`. After reconnect and
   `reconcile()`, every file still present is stored exactly once (files created and deleted meanwhile are not, per `STATE_SNAPSHOT`). With a `NON_REPLAYABLE` test trigger, the same run
   reports the lost count in `dropped` / `emitTimeouts`, never silently.
9. Crash loop leads to `Failed/CrashLoop`, and `Run` resets it.

**Scoped lifecycle** (groups `g1` = {d1, d2}, `g2` = {d2, d3}):
10. `Pause g1` → d1 and d2 `Paused`, d3 `Running`. `Stop d3`, then `Run g2` → d3 stays `Stopped` and d2 stays
    `Paused`. `Remove d1` → `Removed`, excluded. `Clear g1` → d2 `Running` from its checkpoint. A new d4 joining `g1`
    while paused starts `Paused`.
11. A rolling upgrade on `g1 ∪ g2` with `maxUnavailable: 1` never has two instances unready at once. A failing new
    version stalls the rollout, and `:rollback` restores it.

**Either side:**
12. A `@ServerTrigger` without routes or secrets runs on both a pool and a device. The IMAP trigger's client allocation
    is `Unsupported`.

**Multi-tenant (PostgreSQL):**
13. Tenant B can't read, allocate, act on, place on, lease, ingest into, checkpoint for or redeem secrets for tenant A's
    resources. Each attempt gets the response for a nonexistent ID.

**Schedule → socket → MCP:**
14. A Quartz schedule `0/30 * * * * ?` publishes `org.acme.heartbeat.v1`. A user subscribed over the WebSocket gets
    each event once. Disconnect for 2 minutes and resume: the missed events arrive, followed by live ones. The action
    calls a read-only tool as `agent:ops-automation`, and `action.completed` events correlate by `causationid`.
15. Restart Control across a fire time with `misfirePolicy: FireOnceNow`: exactly one catch-up event. With `Skip`:
    none. Two Control replicas on PostgreSQL never store a fire twice.
16. Remove the subscriber's grant: the socket gets `revoked` and closes. A ticket replayed after use is refused.

**Device trigger → authorized MCP:**
17. `examples.inbox-watch` on d1 fires for `a.pdf`. The action runs a client tool on `OriginatingDevice` d1 as the
    run-as Agent, with the producer recorded in audit. Re-emitting the same event (`reconcile()`) gives `EXISTING` and
    no second run.
18. Setting `placement: OriginatingDevice` for a device outside the tool's binding group denies the action. An
    `event.data` field naming another device has no effect on placement.
19. An action to a `NON_IDEMPOTENT` tool waits for ASK approval. Denying it ends the invocation as `CANCELLED`, and the
    event is not retried.

**Composite MCP:**
20. Upload `documents.first-text` as YAML and the same definition as JSON: identical digests. Validation rejects a
    type error (`steps.list.result.file` misspelled) and an unknown child tool.
21. An agent calls the composite through the Gateway: two child invocations, each with its own policy decision. An
    empty folder returns `no_files` with one child call. A zero-step composite returns its computed result with none.
22. An agent without a grant on `files.read_text` gets an authorization error at step `read`, with step `list` still
    recorded. A caller input overriding a non-overridable global is refused.
23. Kill Control during step `read`. On restart, `list` is not repeated and `read` is reconciled by its business key.
24. A credential slot reaches a child that opted in. A child without the opt-in uses its own binding. The credential
    value never appears in the composite's result, events or logs.

**Review findings (regression scenarios):**
25. **Ambiguous effect:** the target commits, then the executor is killed before reporting. The action invocation is
    `OUTCOME_UNKNOWN`, and a redelivered event gets `EXISTING`, not a new run. With a reconciler, the invocation
    settles. Without one, it waits for human review and is never retried.
26. **Schedule drift:** crash between the assignment commit and the Quartz update, delete a Quartz job by hand, and
    plant a stale-generation job and an orphan job. Full reconciliation repairs all four. A fire racing `Stop` is
    skipped by the guard (`NotRunning`).
27. **Credential provenance:** an event, an action argument or a composite input that names a different vault
    reference has no effect. A principal outside the slot's allowed set fails with `CredentialBindingRejected`. A child
    opt-in pinned to an older composite version digest is refused. Rotating the secret mid-run: the next step uses the
    new version. Revoking it mid-run fails the next step.
28. **Resource scope:** the listing returns `../x`, an absolute path, a symlink and a hard link. `files.read_text`
    refuses each one; the composite does not need to.
29. **Checkpoint barrier:** IMAP or file events A, B, C, where B and C become durable before A. The checkpoint does not
    pass A until A is durable. Killed in between, the restart re-emits A, and storage dedupes B and C.
30. **Pending actions:** events queued, then `Pause`: the deliveries become `Held`, and `Run` releases them. Events
    queued, then `Stop`: they are `Skipped (AssignmentStopped)`, and no invocations are created.
31. **Socket hygiene:** ticket values never appear in access logs, traces or audit. A filter that references a
    classified field is rejected for an owner whose clearance is below its label. Replay after grant loss skips the
    now-unauthorized events.
32. **Offline device:** a device with a valid 24 h lease but no Control connection runs its triggers and queues events,
    and starts no action. Actions start only after Control ingests the events.

**Second review (regression scenarios):**
33. **Rejection classes:** A is accepted, B gets a retryable rejection (rate limit), C is accepted, then restart. The
    checkpoint never passes B, and B is re-emitted and stored. With B rejected terminally under `HALT`: the instance is
    `Failed (TerminalRejection)` and the checkpoint stays before B. Under `SKIP_AND_RECORD`: a loss record is stored,
    and the accepted watermark passes B only after Control acknowledges that loss record. The same three cases with the
    rejection made by Control instead of locally (`retryLater`, `dispositions`).
34. **Pinned action revision:** an event is queued for a read-only action, then the action is changed to a
    write-capable tool. Under `Supersede`, the delivery becomes `Superseded` and nothing runs. Under `ExecuteOriginal`,
    the read-only revision runs. If that revision is revoked first, the delivery becomes `Superseded`. The new revision
    never runs for the old event without an explicit, audited re-drive.
35. **Information flow:** for an `INTERNAL`-cleared subscriber, a filter on a `CONFIDENTIAL` field is a compile error,
    `has()` or map access cannot reach it, and deliveries omit it. An action whose `when` reads a `CONFIDENTIAL` field
    produces outcome events that this subscriber never receives. A composite cannot pass a `CONFIDENTIAL` step output
    into a child input limited to `INTERNAL`, either at publish or at runtime. Lowering the `runAs` clearance marks the
    action `ActionInvalid`.
36. **Source classes:** a `STATE_SNAPSHOT` file trigger offline while a file is created and deleted does not report it,
    and the guarantee text in the console says so. An `EVENT_LOG` test source replays every retained event from the
    checkpoint, and reports a `gap` for events the source expired.
37. **Schedule catch-up:** after 5 missed occurrences with `FireOnceNow`, exactly one event with the `id` of the
    latest occurrence and `tgmissedcount: 5`. It is the same id whether produced by Quartz, the reconciler or a restart,
    and two replicas plus the reconciler produce one row. Occurrences of a superseded generation and of a paused span
    are not caught up.
38. **Sequence namespaces:** across a trigger process restart inside one lease epoch, `outboxSeq` continues without
    gaps while `emitSeq` restarts at 1. Acks and checkpoints after the restart refer only to `outboxSeq`, and a new
    lease epoch restarts `outboxSeq` at 1.

**Third review (regression scenarios):**
39. **Crash points:**
    - kill the supervisor after it chooses an `outboxSeq` but before fsync; kill it after fsync but before `EMIT_ACK`;
    - drop Control's ack after commit;
    - reconfigure a client (new epoch) with 50 records queued offline: they drain under the old epoch;
    - fail over a server with records queued on the old host: they are fenced, and the new host recovers from the
      `ACCEPTED` checkpoint.

    No checkpoint or `acceptedOutboxSeq` passes an unresolved event. A permanently lost host running a
    `NON_REPLAYABLE` test trigger produces `Degraded (UnrecoverableGap)` with a lower-bound count.
40. **Late replay after tombstone expiry:** complete a `NON_IDEMPOTENT` action, purge the event and its dedupe
    tombstone (simulated clock), then have the source re-deliver the same id. The event is stored again, but the
    operation ledger returns `EXISTING` and no invocation is created. A `READ_ONLY` action past its 30-day retention is
    refused as `TooOldForAction` when the event is older than the retention window.
41. **IMAP identity:** change `UIDVALIDITY` on the test server. The trigger rescans and reports the current messages
    once under new ids, and never maps a reused UID onto an old event. Deliver `messagesAdded` callbacks out of order:
    the result is identical to in-order delivery.
42. **File identity:** rewrite a file three times between reconciles (one version reported); rename a file (no new
    report); delete and recreate it with the same name (a new version is reported).
43. **Running operation, authorization change:** revoke the `runAs` grants while a brokered child is executing. The
    next broker call is refused within 5 s, and the invocation ends with the stopping mechanism recorded. Withdraw an
    approval after consumption: it acts as a cancel. Revoke a `CREDENTIALS_IN_SANDBOX` credential mid-run: the
    documented limit is visible in the audit record.
44. **IFC conformance:** the whole `tests/fixtures/ifc/` corpus, including malicious tool responses and a signed schema
    claiming a lower label than its inputs or binding floor, which must not downgrade.

**Fourth review (regression scenarios):**
45. **Failover with unaccepted old-host records** (`EVENT_LOG` and `STATE_SNAPSHOT` test sources):
    - the old host fsyncs records 5–9 of epoch 1, Control accepts only 1–4, then the old host is partitioned and its
      lease expires;
    - the new host (epoch 2) starts at the checkpoint after 4 and never advances past the source position of record 5
      until record 5's event is `STORED`;
    - records 5–9 replayed by the new host are stored, because no dedupe key exists for fenced records;
    - when the old host reconnects, its epoch-1 records are fenced and nothing is stored twice.
46. **Compromise vs failover:**
    - revoke a device as `Compromised` with records still queued: they are fenced, and events received after
      `distrustFrom` are `QUARANTINED` with no deliveries or actions;
    - an action already started from such an event is listed for review;
    - an ordinary decommission revocation keeps already-stored events deliverable.
47. **Late replay and trusted time:**
    - a previously seen id re-delivered a year later (simulated clock, tombstones purged) with a forged recent source
      `time`: `EXISTING` from the ledger, nothing runs;
    - a genuinely new id with a forged ancient source `time` is processed. `ignoreIfSourceTimeOlderThan` can only skip
      it, never cause a run.
48. **New revision is not a repeat:**
    - change an action's target after an operation succeeded, then replay the event: `EXISTING`, no run;
    - `:redrive` on an operation with an attempt is refused;
    - `:retry` after `OUTCOME_UNKNOWN` without reconciliation is refused unless the target has a current reviewed
      guarantee (ADR 056 qualified resend).
      With reconciliation ("not applied"), it creates attempt 2 with the **same** `effectId`, and a target that honors
      idempotency sees the same key;
    - `:repeat` without a reason, without the second approver for a financial-domain operation, or while an earlier
      effect is an unreconciled `OUTCOME_UNKNOWN`, is refused;
    - an approved `:repeat` creates effect key `1` with a new `repeatId` and its own consent slot. The test target, which honors
      idempotency, **performs** the new effect, because it receives a new idempotency key. The original effect's
      history is unchanged and linked by `operationKey`.
49. **Cleanup transitions:**
    - outbox pruning never removes an unacknowledged record;
    - event retention expiry leaves `acceptedOutboxSeq`, checkpoints and the ledger unchanged;
    - a checkpoint migration either migrates exactly or restarts from the beginning;
    - removal with 20 undrained records reports the count;
    - reinstallation resumes from Control's `ACCEPTED` checkpoint;
    - a replayed event after a crash gets a new `emitSeq` and `outboxSeq` with the same `id`.
50. **Fail-closed defaults and side channels:**
    - unlabelled output from a remote MCP server is `CONFIDENTIAL` by default, and `RESTRICTED` after the tenant raises
      it;
    - a tool leaking a classified value through error text, `subject`, logs or progress frames is redacted for an
      under-cleared reader.
51. **Snapshot capacity:**
    - a listing that fails half-way prunes nothing;
    - a test source exceeding `SnapshotState` capacity reports `Degraded (SnapshotCapacityExceeded)`, does not advance
      its checkpoint, and switches to durable rescan mode with dedupe by `id`.

**Fifth review (regression scenarios):**
52. **Retry vs repeat identities:** see scenario 48. In addition, a composite that is repeated gets new child
    idempotency keys `<newEffectId>:<stepId>`, while a retried composite reuses its child keys.
53. **Check-in size and checkpoint transfer:**
    - a Tool Host with 256 instances pages its snapshot, and Control keeps the previous complete snapshot until every
      page of the new digest has arrived;
    - a 128 KiB checkpoint is uploaded in chunks; a corrupted chunk fails the commit digest check and the previous
      version stays current;
    - a commit with `afterOutboxSeq > acceptedOutboxSeq` is refused;
    - a client after disk loss downloads and verifies the `ACCEPTED` checkpoint before resuming.
54. **Bounded resource safety:** every test in section 20.2 passes.
55. **SDK trigger conformance:** the shared fixtures of M2 pass for both Java and Python runtimes and all samples.

**Sixth review (regression scenarios):**
56. **Snapshot generations:**
    - change the inventory while page 2 of 4 is in flight: the executor freezes generation n+1 and restarts from page
      1, and Control never applies a mix of n and n+1;
    - a page with a wrong `pageDigest`, or a set whose `snapshotDigest` doesn't verify, is discarded and Control keeps
      its previous snapshot;
    - an older generation arriving late is ignored;
    - continuous inventory churn still completes a snapshot after at most 3 supersessions.
57. **Checkpoint upload across a lease change:**
    - upload every chunk of a 128 KiB checkpoint, let the lease expire, then send the commit: `StaleLeaseEpoch`, the
      upload is `Abandoned`, and Control's current checkpoint is unchanged;
    - the failover host starts from the previous committed `ACCEPTED` checkpoint and its own new upload commits
      normally;
    - repeat with the lease revoked mid-upload, and with the same executor under a new epoch: it cannot resume the old
      upload.
58. **Accounting-based resource safety:** in every section 20.2 test, the accounting counters stay at or below their
    caps at every sample, and the processes stay within their fixed memory limits without OOM. No assertion uses heap
    deltas.

**Soak test** (nightly, not a gate): 24 h push listeners with reconnect churn. No growth in memory, file handles or
outbox.

## 22. Deferred (not v1)

| Item | Prerequisite |
|---|---|
| Action chaining (actions on action outcomes), causation depth > 1 | Cycle detection and per-chain budgets |
| Composite compensation (sagas), parallel and `forEach` steps | Compensation semantics per effect class |
| LangGraph or other agent workflows as action targets | Isolated workflow runner ADR |
| WebSocket stream through the Gateway; webhook delivery to external URLs | Gateway streaming support; SSRF-safe outbound delivery |
| Unattended non-idempotent effects without a tenant opt-in | #230 fencing for the target's class (ADR 057 covers two SQL classes; others remain) |
| Inbound webhooks as push sources | A per-tenant inbound endpoint with signature verification and replay protection |
| Brokered streaming routes (SSE, HTTP long-poll through `ROUTE_CALL`) | Broker streaming support. v1 push uses TCP tunnels |
| Client egress and secrets (a mail listener on a device) | Separate ADR (ADR 013 §7) |
| Sharded listeners (several instances per server assignment) | Partitioning model |
| Pinning a server instance to one host | Placement constraints |
| Write file access, write routes | Larger capability review |
| Marketplace distribution, designed UI | Later releases |
| Orchestration workers (router, actions, composites) as a separate service | Load evidence. Control keeps policy and durable invocation state either way (section 15.4) |

## 23. Open questions for review

7. **Action identity:** reuse Agents with a `SERVICE` profile (proposed), or add a distinct automation principal kind?
8. **Socket location:** Control-only in v1 (proposed), or expose it through the Gateway from the start for external
   agents?
9. **Composite orchestrator placement:** in Control (proposed: it owns the ledger, and steps are only admissions plus
   waits), or a separate worker service from day one?
10. **CEL as the single expression language** for subscription filters, action conditions and composites (proposed), or
    the CloudEvents Subscriptions API filter dialect for subscriptions?
11. **Quartz cron syntax only,** or also accept 5-field Unix cron and convert it?

Earlier questions:

1. **Sync carrier latency.** Is the check-in interval acceptable for status? If not, add an optional batch POST of the
   same `TriggerSync` body.
2. **Lease timing.** Is 60 s / 40 s for server leases right, or should it be per pool?
3. **Desired-document trust on Tool Hosts.** Use the fleet key via discovery (proposed), or mTLS only?
4. **`nativeMount` in v1,** or only executor-native watch at first?
5. **Mixed packages.** Can one package contain tools and triggers (proposed: yes)?
6. **Default ceilings.** 3 600 events per hour per instance, 50 000 per hour per tenant, 7-day retention, 24 h client
   lease.

## 24. Implementation plan: one module at a time

Section 21 groups the **scope** into milestones. This section fixes the **execution order**: modules are built
strictly one at a time, and each is finished (implemented, tested, documented and merged) before the next starts. The
point is to test each piece once, when it is built, and not re-test half-finished work across modules.

### 24.1 Working rules

1. **One module in progress per track.** A module starts only when every module it depends on meets its *Done when*.
   Modules with no dependency between them and no shared code may run at the same time on separate tracks (24.5).
   Within a track, modules run in sequence.
2. **Vertical and complete.** Each module delivers working behavior through every layer it touches (contract, code,
   tests, docs). No "tests later" and no stubs left for a later module to fill in, except the explicit fakes below.
3. **Test once, at the cheapest layer.**
   - Unit and property tests cover the module's own rules.
   - One integration test covers each new boundary.
   - End-to-end scenarios run only at **slice checkpoints** (marked ★), where a user-visible flow first works.
   - A finished module's tests stay in CI as regression protection. They are never re-planned or rewritten by later
     modules.
4. **Finished modules are trusted black boxes.** Later modules use them through their public contract. A later module
   that needs a not-yet-built neighbor uses a **fake** at that boundary: the router stores events with no deliveries,
   and actions have no composite targets yet.
5. **Behind feature flags.** Each module ships behind `toolgate.triggers.<module>` (off by default) until its slice
   checkpoint passes, so `main` stays releasable after every module. When the slice checkpoint passes, the flag and
   its "off" branch are deleted (section 0): the release has one production code path and no trigger flags.
6. **Scenario ownership.** Every M12 scenario is assigned to exactly one module (24.3), and passes when that module
   finishes. The final module re-runs the whole suite once, as the release gate.
7. **Effort percentages** are each module's estimated share of the **total** v1 implementation effort, including that
   module's own tests and docs. They are relative estimates for planning and tracking progress, not calendar
   commitments. The total is 100%.
8. **Tenant isolation inside every module.** Each module's *Done when* includes the cross-tenant negative tests for
   the routes, records and paths it adds (section 17). Scenario 13 is therefore completed incrementally, not in one
   place.
9. **Fresh build.** Every module follows section 0: no backward compatibility, no database migrations and no legacy
   or outdated code. Schema changes go into the fresh `V1` schema; contract changes are made in place and the
   bindings regenerated.

### 24.2 Module sequence

| # | Module | Effort | Cumulative | Builds | Depends on | Done when |
|---|---|---|---|---|---|---|
| 1 | **Contracts, ADR and model** | 5% | 5% | ADR 058; `v2/trigger.schema.json` and protocol frames; generated Java, Rust, PHP and TS bindings; fixtures; proposed OpenAPI; descriptor keyword amendment; `trigger_delivery.tla` | — | Gate D0 approved; contract round-trip tests pass; the check-in and ack worst-case size contract test passes (8.5); TLC passes on the model (6.6.1) |
| 2 | **Java SDK, POLL** | 6% | 11% | `@ClientTrigger` and `@ServerTrigger` (POLL), processor rules, `PollContext`, runtime loop and frames, `toolgate check` and `toolgate pack`, harness; sample `inbox-watch` in POLL form | 1 | Processor compile-error tests (5.8), runtime frame tests, harness tests for the sample; the package packs and verifies |
| 3 | **Supervisor crate** | 8% | 19% | `crates/trigger-supervisor`: process lifecycle, frames, outbox with `outboxSeq`, the `LOCAL` barrier, rejection classes, loss records, backoff and crash loop, health, the sequence namespaces | 1 | Stateful property tests against the model (G-STATE item 2) with a fake runtime and a fake Control; crash-point tests T1–T5 and T10 |
| 4 | **Control core for client triggers** | 8% | 27% | Package acceptance of trigger exports and the security delta; the definitions API; `TriggerAssignment` CRUD for **client** targets (ETag, idempotency, dry run); instances; the signed `TriggerDesiredDocument`; client leases; permissions; audit; tenant tables | 1, 2 | API tests including cross-tenant negatives; desired-document signing and verification tests |
| 5 | ★ **Client integration and ingest** | 8% | 35% | The endpoint client links the supervisor, fetches desired state and adds `TriggerSync` to check-in; Control ingest (T6–T9, dedupe, `acceptedOutboxSeq`, acks, `retryLater`, dispositions); instance and event queries; derived `Stale` | 3, 4 | **Slice 1:** allocate a POLL trigger to a device, see `Running`, receive events, query status. Owned scenarios pass |
| 6 | **Lifecycle, rollout and raw page** | 6% | 41% | `:action` with overrides and precedence; `excludeDevices`; rolling package upgrade and `:rollback`; the raw `#triggers` page (upload, assignments, actions, status) | 5 | Override-resolution unit tests; owned scenarios pass |
| 7 | **PUSH mode and file watching** | 7% | 48% | Java `PushTrigger`, emitter, position barrier, `ACCEPTED` level (checkpoints ≤ 4 KiB inline), `requestReconcile`, `SnapshotState`; client `FILES_WATCH` and `FILES_INFO` `versionKey`; sample `inbox-watch` in PUSH form | 5 | Java part of the SDK trigger conformance fixtures (M2); owned scenarios pass |
| 8 | ★ **Server triggers on the Tool Host** | 8% | 56% | Tool Host links the supervisor; server placement, singleton lease and failover; the lease-bound broker and secrets; snapshot paging with generations (8.5); chunked checkpoint transfer (8.6); compromise quarantine (T15); sample `mail-watch` (POLL) | 7 | **Slice 2:** a server mail trigger in a two-host pool, with failover. Owned scenarios pass |
| 9 | **Python SDK parity** | 4% | 60% | `@server_trigger` and `@client_trigger`, POLL and PUSH, `SnapshotState`, and the Python twins of all samples | 2, 7 | The full shared SDK conformance fixtures pass for Python and Java |
| 10 | **Event router and retention** | 4% | 64% | Transactional outbox, router workers, the delivery state machine (with `Held` and `Superseded`), dead letters and re-drive, retention, tombstones, purge (T11), bulkheads (20.1) | 5 | Router unit and integration tests; retention tests. Deliveries go to a test consumer only |
| 11 | **Information flow, coarse mode** | 3% | 67% | Labels per event type and tool output, `dataClearance`, fail-closed defaults per integration kind, redacted views, restricted CEL environment (`dev.cel`) with limits | 10 | Coarse-mode tests and CEL limit tests. This is the safe baseline that lets the following modules be built without the field-level work |
| 12 | **Subscriptions and WebSocket** | 5% | 72% | `TriggerSubscription` with CEL filters on the readable view; single-use tickets with log redaction; `toolgate.triggers.v1` with credits, resume, gaps and revocation; AsyncAPI; the console Live panel | 10, 11 | Protocol tests and ticket-hygiene tests; owned scenarios pass |
| 13 | **Schedules (Quartz)** | 5% | 77% | Schedule source; configuration outbox and reconciliation controller; fire-time guard; catch-up identity (11.3); `status.schedule` | 10 | DST, race and catch-up tests; owned scenarios pass |
| 14 | ★ **Actions** | 8% | 85% | Action revisions and pinning; Control-originated admission (`origin: TRIGGER`); the run-as Agent and `agent.delegate`; actions on the #230 operation, attempt and effect records (ADR 034–036, 053–057) with explicit effect keys, plus the thin delivery and repeat tables; `:redrive`, `:retry` (ADR 056 resend rules) and `:repeat` (new effect key and consent slot); dispatch re-authorization; effect rules; `OUTCOME_UNKNOWN` handling; authorization changes during runs (14.4.1); `OriginatingDevice` | 12, 13; #230 S21 (delivered) | **Slice 3:** schedule → socket → MCP, and device trigger → authorized MCP. Owned scenarios pass |
| 15 | ★ **Composite MCP** | 7% | 92% | Draft, validate, test and publish; canonical digest; CEL type-checking; globals; credential slots with provenance; the durable orchestrator; resource-scope rules (15.4.1); the Gateway admission change; composites as action targets | 14 | **Slice 4:** a composite run by an agent and by an action. Owned scenarios pass |
| 16 | **Information flow, field-level mode** | 4% | 96% | The schema-walk labeller for nested data, binding floors, side-channel enforcement, and the full conformance and adversarial corpus | 11, 15 | **Gate G-IFC** passes and field-level mode is switched on. If it doesn't pass, v1 ships in coarse mode and this module moves after release |
| 17 | **Resource safety and release gate** | 3% | 99% | Section 20.2 tests with accounting counters; the nightly deterministic simulation (G-STATE item 3); one full M12 run on the release commit | all above | **G-STATE**, **G-IFC** (or coarse mode) and every M12 scenario pass |
| 18 | **IMAP PUSH sample** (blocked) | 1% | 100% | `mail-idle` in Java and Python, once Tool Host TCP tunnels (ADR 013) exist | 8, and Tool Host tunnels | IMAP scenarios pass. Not a v1 blocker: if tunnels aren't ready, it moves after release |

**Progress view by slice:** Slice 1 at 35%, Slice 2 at 56%, Slice 3 at 85%, Slice 4 at 92%, release-ready at 99%.

### 24.3 Which module owns which M12 scenario

Each scenario must pass when its owning module finishes. Module 17 re-runs all of them once.

| Module | Scenarios it closes |
|---|---|
| 5 Client integration and ingest | 9, 13 (allocation and ingest parts), 33, 38, 39 (client crash points) |
| 6 Lifecycle, rollout and raw page | 10, 11 |
| 7 PUSH and file watching | 7, 8, 29, 36, 42, 51, 55 (Java), 58 (outbox part) |
| 8 Server triggers | 1–6 (POLL variants), 12, 45, 46, 49, 53, 56, 57 |
| 9 Python parity | 55 (Python) |
| 12 Subscriptions and WebSocket | 16, 31 |
| 13 Schedules | 15, 26, 37 |
| 14 Actions | 14, 17, 18, 19, 25, 30, 32, 34, 40, 43, 47, 48, 52 (non-composite part) |
| 15 Composite MCP | 20–24, 27, 28, 52 (composite part) |
| 16 IFC field-level | 35, 44, 50 |
| 17 Resource safety and gate | 54, 58 (accounting), plus the full suite |
| 18 IMAP PUSH | 41, plus the IMAP variants of 1–6 |

### 24.4 What each module hands to the next

| After module | The next module can rely on |
|---|---|
| 1 | Frozen contracts and generated bindings; no schema changes without an ADR amendment |
| 3 | A supervisor that is correct against the model; executors only integrate it |
| 5 | A working client trigger with status and events; later work only adds features |
| 8 | Both executors on one protocol; server features need no new transport |
| 10 | A durable event stream with deliveries; consumers (sockets, actions) plug in |
| 11 | A safe expression environment; every CEL user from here on is protected |
| 14 | Governed execution from events on the #230 effect path; composites are just one more target kind |

### 24.5 Parallel tracks and coordination with #230

Triggers and #230 change some of the same files. Based on `main` at `bb3b078d` (#230 through S21), modules are
grouped into tracks that can run at the same time:

| Track | Modules, in order | Main code areas | Can start |
|---|---|---|---|
| A — Contracts and model | 1 | `packages/contracts/schemas/v2`, generated Java/Rust/PHP/TS bindings, `generated-manifest.json`, `docs/adr/058-*`, `docs/specs/trigger_delivery.tla` | Now |
| B — SDK | 2 → 7 (SDK part) → 9 | `tool-sdk/java-sdk` (api, processor, runtime), `tool-sdk/python-sdk`, `tool-sdk/cli` | After module 1 |
| C — Supervisor | 3 | New `crates/trigger-supervisor` | After module 1 |
| D — Control | 4 → 6 → 10 → 11 → 12 → 13 | New trigger packages under `apps/control-plane/.../control/{application,adapter}`, fresh `V1` schema, `apps/admin-ui` raw page | After module 1 |
| E — Executors | 5 → 7 (client part) → 8 | `apps/endpoint-client/src` (check-in, `files.rs`), `apps/tool-host/src` (`lease.rs`, `control.rs`, `broker*.rs`) | After modules 3 and 4, and the S22 check-in agreement below |
| F — Governed execution | 14 → 15 → 16 → 17 | Control action/composite packages on the #230 effect services (`EffectGrants`, `TargetClaims`, `OperationFinalizer`), Gateway admission | After modules 12 and 13 |

**Shared files, one owner at a time:**

- **Fresh schema** (`db/migration/V1__initial_schema.sql`, `db/quickstart/V1.sql`, `db/schema-ownership.json`): one
  named schema owner merges trigger and #230 table changes. Trigger tables go in one clearly marked block.
- **Generated contracts** (`generated-manifest.json`, `operations.generated.ts`, `ContractCodec` and the PHP, Rust and
  TypeScript bindings): regenerate after every merge from `main`; never hand-merge generated output.
- **OpenAPI** (`packages/contracts/openapi/control-v1.yaml`): trigger routes go in their own tagged section.

**Coordination points with #230:**

| #230 stage | Overlaps with | Rule |
|---|---|---|
| S22 push/long-poll kill and inbound mTLS dispatch | Module 5 (`TriggerSync` in check-in), module 8 (Tool Host leases and Control channel) | Agree one check-in and Control-channel extension with the S22 owner before module 5 starts. Both features use it; neither adds a second channel |
| S23 outcome grades | Module 14 console and `action.completed` outcomes | Action outcomes use the S23 grades once delivered; until then, they show the same grades as `main` |
| S27/S28 run identity and narrowing delegation | Module 14 run-as Agent and `agent.delegate` | `agent.delegate` is built as, or replaced by, the S28 delegation chain; there is no second delegation model |
| S30 broker OAuth and token exchange | Module 8 lease-bound broker and secrets | Trigger leases use the same broker authority as permits; S30 token exchange applies to both |
| S31 trusted provenance | Modules 11 and 16 information flow | Event and tool-output labels feed S31 provenance; one policy engine |

When a #230 stage lands first, the trigger module builds on it as delivered. When a trigger module lands first, the
#230 stage extends that code rather than adding a parallel path (section 0).

### 24.6 Delivery grouping (decided 2026-11-07)

The modules of 24.2 keep their scope, weights, dependencies and *Done when*. What changes is how they are delivered
next:

1. **Module 5** (client integration and ingest, Slice 1) in its own PR, as before.
2. **Modules 10, 11 and 12 and an end-to-end demo in one PR.** The event router and retention, coarse-mode
   information flow, and subscriptions over WebSocket are built in sequence on one branch. The demo runs the first
   user-visible path: a device POLL trigger, through Control ingest and the router, with coarse labels applied, reaches
   an external listener subscribed over `toolgate.triggers.v1`. Each module still has to meet its own *Done when*
   before the PR merges, and the status document records the evidence per module. Merging the PR earns all three
   weights (12%). The demo has no separate weight; it is the evidence for scenarios 16 and 31 (module 12).
3. **Everything else in later deliveries:** modules 6 to 9 and 13 to 18, in the order of 24.2 and the tracks of 24.5.

This supersedes the "one branch and one PR per module" rule for modules 10–12 only.
