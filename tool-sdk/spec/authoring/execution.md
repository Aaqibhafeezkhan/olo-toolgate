# Execution semantics

## Purpose

How a handler is invoked, cancelled and retried; how effects, business keys, reconciliation and examples behave. Identical in both runtime modes unless a row says otherwise.

## 1. Invocation

- **One handler call per invocation.** The SDK never re-invokes a handler. Retries are decided outside the tool (plan.md §11.4), and only for tools whose effect is `READ_ONLY` or `IDEMPOTENT`.
- **Input validation** against `inputSchema` happens before the handler runs; **output validation** against `outputSchema` happens after. A non-conforming output becomes `INTERNAL_TOOL_ERROR`.
- **Size ceilings:** input 1 MiB and output 4 MiB on Tool Host and in standalone mode; on endpoint clients the existing v1 limits (64 KiB, 32 properties) apply until a client release raises them.
- **No state across invocations.** Tools must not rely on process state between calls. Governed mode uses single-use sandboxes; standalone mode may reuse a process.
- **Concurrency:** handlers must be reentrant; `maxConcurrency` is enforced by both runtimes (excess calls wait up to 1 s, then fail with platform code `CONCURRENCY_LIMIT`).

## 2. Deadlines

`invocation().deadline` is identical in both modes: request time plus `maxDurationMs`, capped by the platform ceiling (60 s sync, 15 min task) and by the caller's credential expiry. Synchronous server calls are further capped by the Gateway's request timeout (at most 30 s today), so anything longer **must** be `async`.

## 3. Cancellation

- Cooperative first: `isCancelled()` and the language token (`CancellationToken`, `AbortSignal`, `threading.Event`-backed token, Java `CancellationToken` wrapper over interrupt) become set.
- After a grace period (default 5 s) the runtime stops the handler: sandbox kill in governed mode; abandon and discard in standalone mode.
- A tool that catches and swallows cancellation still ends `CANCELLED`.
- In-flight `http()` calls are aborted on cancellation; for ledger tools an abort after send sets `effectUncertain`.

## 4. Effects and business keys

| `effect` | Execution | SDK HTTP client |
|---|---|---|
| `READ_ONLY`, `IDEMPOTENT` | At least once | May retry per route policy |
| `NON_IDEMPOTENT`, `UNKNOWN` | At most once per invocation | Never retries; never follows redirects |

- `requiresExecutionLedger = effect ∈ {NON_IDEMPOTENT, UNKNOWN}`. It is resolved at enable and carried in the permit; nothing re-derives it.
- **Business keys:** sent in `Idempotency-Key` or in `businessKeyArgument`. Namespaces `AGENT` (key unique per agent), `DELEGATED_USER` (per verified user; SERVICE calls rejected with `BUSINESS_KEY_NAMESPACE_MISMATCH`) and `TENANT` (per tenant). Outcomes: `NEW`, `EXISTING` (same result returned), `IDEMPOTENCY_CONFLICT` (different fingerprint, same caller), `IDEMPOTENCY_KEY_IN_USE` (different caller; reveals nothing), `EXPIRED_KEY`.
- **Missing key policy** for `requiresBusinessKey` tools: `REJECT` (default), `ASK`, or `ALLOW_WITH_DEDUPE` (heuristic, documented as such).
- **Forwarding upstream** happens only with `Idempotency(header, scope)`: `TENANT` sends the raw key; `AGENT` sends `a.<agentIdHash>.<key>`; `DELEGATED_USER` sends `u.<userIdHash>.<key>`; with no key, a value derived from `invocationId`.
- **Standalone mode** implements the same outcomes with a local SQLite reservation store.

## 5. Reconciliation

A handler marked `Reconcile` receives `{businessKey?, invocationId, arguments}` and returns `APPLIED`, `NOT_APPLIED` or `UNKNOWN` with evidence `{upstreamRecordId?, committed: boolean, detail?}`. It is called only for invocations that ended `OUTCOME_UNKNOWN`. Re-execution after `NOT_APPLIED` is opt-in, uses the same business key and a new `invocationId`, and is never automatic.

## 6. Tasks

- `async = true` tools always run as tasks. A client that doesn't declare the Tasks extension on the request doesn't see them in `tools/list` and gets `MissingRequiredClientCapability` (-32021) on a direct call, unless an admin enabled bounded sync mode.
- `progress()` updates the task status message; `requestInput()` moves the task to `input_required` until answered through `tasks/update`.
- In a sync call `requestInput()` is MRTR (SEP-2322): the round ends with an `InputRequiredResult` and `requestState` ([protocol-layer.md](../protocol-layer.md) A3), and the handler runs again from the start with the answers. Code before `requestInput()` must be safe to repeat.
- `cancelIfAbandoned`: no poll within 3 × `pollIntervalMs` requests cancellation.

## 7. Examples and self-tests

`Example(arguments, expectedOutput?, expectedError?, exchanges?)` entries (at most 8 per tool) run in `check`/`test` builds in test mode, and map onto the existing fleet self-test fields for client tools and onto Tool Host self-tests for server tools. An example must not need network access: its route calls are answered only by the recorded exchanges its `exchanges` resource holds ([testing.md](testing.md)). Examples are not part of the descriptor; the build's package index carries them.

## 8. Logging

`log()` is the only supported channel. Entries are redacted ([errors.md](errors.md) §4), capped at 4 KiB each and 256 KiB per invocation, kept locally in standalone mode and attached to audit diagnostics in governed mode.

## Related Docs

- [errors.md](errors.md), [http.md](http.md), [testing.md](testing.md), [../runtime-modes.md](../runtime-modes.md)
