<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Zero Trust Execution implementation status

The [revision-10 plan](../plans/zero-trust-tool-execution.md) is the preserved
proposal linked by issue #230. A merged design or an authored conformance case
does not establish a passing security claim.

## Overall task progress

Updated: **2026-10-31**. Branch: `feature/zero-trust-execution-plan`.
Previous containment delivery: [`0813b71b`](https://github.com/olo-labs/olo-toolgate/commit/0813b71bbe2a0f9276a1b2e2260b25454a31d3d4).

**Overall: partially implemented.** Six scoped deliveries are implemented:
renewable leases/durable execution recovery, qualified Python OCI containment,
explicit business-key binding, independent operation/permit-attempt journaling, an effect-generation foundation, and exact broker transport preparation. Executed validation for the current
delivery is recorded below. **0 of 7 full roadmap phases have met every exit
criterion.** Phases 0–3 have
partial delivery; phases 4–6 have not started as generalized implementations.
No percentage is assigned because the phases differ substantially in scope.

| Plan phase | Status | Delivered | Remaining before full phase completion |
|---|---|---|---|
| 0 — Boundary and harness | Partial | Preserved revision-10 plan, claim inventory, scoped documentation and executable OCI conformance gate with revision/source/image evidence. | Adopt the full threat matrix across security docs; execute all C1–C8 conformance cases and publish a complete release scorecard. |
| 1 — Operations, leases and evidence | Partial | Durable device/fence-bound leases; heartbeat/watchdog; admission spool; result outbox; immutable late executor evidence; SQLite/PostgreSQL migrations and tests; conservative business-key binding; V3 operation/attempt records, immutable snapshots, historical backfill, positive-proof reservation replacement and conservative operation summaries. | Generalized requiresBusinessKey/namespace/dedupe-window policy, retention maintenance, whole-invocation rebinding and full effect-aware operation finalization/caller integration; complete admission/rebinding and crash conformance. |
| 2 — Containment | Partial; Python worker slice delivered | Rootless Podman/crun workers; permit-bound image; isolated namespaces and UID ranges; read-only files; enforced resource limits; stdio broker; teardown/restart recovery; no native fallback; service unit and CI gate. | Separate the trusted broker's OS identity/network namespace as specified by the plan; broader deployment/interface conformance and deployment SLO evidence. Other languages and VM tiers require separate qualification. |
| 3 — Effect authorization and recovery | Partial; ledger foundation | Durable immutable effect identities; authorization generations/owners; release/send/consume/ambiguity states; target-vs-broker evidence; timeout and terminal-invocation abandonment. Integrated with existing OLO write permits. Broker-owned prepared HTTP requests now have versioned fingerprints and explicit no-retry transport. | Wire HTTP fingerprints into Control grants and target checks; generalized broker enforcement, effect keys/manifests, real broker instance/handler protocol, consent slots, target fencing SDK/shared claims, deduplication, reconciliation and operation finalization. |
| 4 — Evidence presentation and tamper evidence | Not started | Raw executor evidence is retained by phase 1. | Outcome grades in presentation/exports, tenant audit chains, signed external anchors and offline verification. |
| 5 — Identity hardening | Not started | Existing device certificates remain the baseline. | Sender-constrained agent-run credentials, narrowing delegation and continuously verified executor posture. |
| 6 — Policy differentiation | Not started | Existing approvals remain the baseline. | Broker OAuth/token exchange, trusted provenance, exact effect previews and stronger step-up approvals. |

### First vertical-slice acceptance

The complete first milestone in issue #230 is **not yet accepted**.

| Acceptance item | Status | Evidence / outstanding work |
|---|---|---|
| Exact consent rejects recipient changes and additional effects | Not implemented | Requires phase-3 request binding and consent slots. |
| Revocation refuses subsequent sensitive effects and stops healthy workers | Partial | Lease revocation tears down a real OCI SDK worker; generalized sensitive-effect authorization and measured deployment bounds remain outstanding. |
| Crashes around dispatch/target commit preserve evidence and prevent unsafe retries | Partial | Admission/result recovery, database reopen, immutable attempt snapshots and refusal of consumed-attempt replay are tested; target-commit/effect-ledger crash scenarios remain outstanding. |
| Partition, duplicates and concurrent target replicas exercise fencing/recovery | Partial | Host Control-partition and OCI recovery tests pass; cooperating-target fencing and replica races remain outstanding. |
| Operation finalization includes all attempts/effects and late evidence | Partial | Each permit has independent identity/snapshots and late evidence. New operations finalize FAILED only without consumption; consumed terminal work remains unknown. Complete effect aggregation and caller-visible finalization remain outstanding. |
| Revision-bound evidence distinguishes fenced/unfenced guarantees | Partial | OCI report for `0813b71b` records six executed tests, source digest, image and architecture. Target guarantees and the complete release scorecard remain outstanding. |

**Next implementation target:** connect prepared HTTP fingerprints to an
authenticated generalized broker effect-grant protocol at Control; then manifests,
consent slots, target shared claims and effect-aware operation finalization. Whole-invocation rebinding, generalized key policy and public history
presentation remain outstanding. Only an expired unconsumed permit may be replaced
inside its operation's original deadline; consumed attempts remain fenced.

### Progress maintenance

For every future issue-230 implementation change, update these tables and the
claim inventory **in the same commit as the code**, then push them together to
the feature branch. Record delivered scope, executed validation and remaining
work. A scoped delivery or passing subset must not mark an entire phase complete.
Use a known tested revision or source digest; never invent the SHA of a commit
that has not yet been created. Repository instructions in [AGENTS.md](../../AGENTS.md)
carry this requirement into future work.

## Security claim inventory

| Claim | Implementation status | Evidence / remaining work |
|---|---|---|
| C1, no ambient target authority | Available for qualified Python OCI pools | Workers have no network socket authority, route, resolver, host mounts or ambient supervisor environment. Native pools do not establish this claim. |
| C2, verified sender-constrained run identity | Partial | Device certificate admission exists; sender-constrained agent runs remain planned. |
| C3a, current admission authorization | Implemented existing boundary | Signed-permit admission and current Control checks; Tool Host `bad_permits_are_refused_before_anything_runs`. |
| C3b, current per-effect authorization | Partial; OLO write path only | Existing OLO current-authority checks now reserve/consume durable effect generations atomically. Generalized broker enforcement, full HTTP binding and the target shared-claim SDK remain required. |
| C3c, bounded unmediated execution | Partial | Native direct-worker supervision and OCI teardown on cancellation/deadline are tested. Enforced service cgroups contain worker processes; measured deployment revocation SLOs remain required. |
| C4, bound admission intent | Implemented existing boundary | Tool Host admission binds signature, audience, arguments and content digests; dispatch integration tests. |
| C5, contained execution | Available for qualified Python OCI pools | Executed rootless namespace, UID, filesystem, socket, syscall, resource, flooding, cancellation and recovery tests. Requires the documented delegated service; other runtime tiers do not inherit the claim. |
| C6, honest tamper-evident record | Partial | Durable admission, immutable executor evidence, independent immutable attempt snapshots and host result retry; standalone SDK SQLite restart/late-completion recovery uses immediate writer transactions. Hash chains and external anchors remain required. |
| C7, revocation | Partial | Renewable leases and current authority checks; full target and compromised-host distinctions remain required. |
| C8, operation/effect integrity | Partial | Single-use admission and durable explicit operation keys exist; keyed unknown outcomes cannot reopen via legacy resend. Independent permit attempts and positive-proof reservation replacement are journaled; all consumed same-row replay is disabled. Effect generations now separate authorization from consumption/outcome on the OLO write path. Generalized key policy, full request binding, consent slots, target deduplication and full operation finalization remain required. |

This is an implementation inventory, not a passing per-release C1–C8 scorecard.
No full-plan acceptance is claimed by this increment. A release scorecard must
retain executed evidence tied to that release's source revision and deployment.

## Lease and recovery verification

- `tools/security/sandbox.py`: mandatory real-rootless containment gate, with
  executed test names, image and source digest under `build/security/sandbox/`.
  See the [pool guide](../operations/rootless-tool-host.md).

- `cargo test -p olo-toolgate-tool-host --locked`: real-host dispatch,
  pre-start refusal, live-worker revocation and Control partition, durable result
  reopen/retry and admission-spool recovery.
- `./gradlew :control-plane:test --tests io.ololabs.toolgate.control.ExecutionLeaseTest`:
  certificate-derived device/fence binding, irreversible expiry, cancellation,
  durable watchdog settlement and late evidence. The full Java gate additionally
  requires the repository's isolated PostgreSQL fixture.
- `python -m unittest discover -s tests/contracts -p test_execution_lease_storage.py -v`:
  shipped SQLite constraints; set `TOOLGATE_D2_POSTGRES=host:port` to execute the
  same PostgreSQL constraint checks against a disposable server.

The [operator guide](../operations/execution-leases.md) states persistence,
rollout, healthy-host limitations and the remaining roadmap work.

## Explicit business-key delivery

[ADR 034](../adr/034-durable-business-operation-binding.md) and the
[caller/operator guide](../operations/business-operation-keys.md) define this
single-attempt slice. Native Gateway headers and modern MCP call metadata resolve
to a stable operation despite new request IDs or Gateway restarts. Control derives
the owner/namespace, binds exact inputs, reserves atomically with invocation/audit,
and refuses owner changes, mismatched inputs, expired retention and ambiguous
resend. The standalone Java SDK task store also reserves SQLite writer authority
before reading transitions, preventing a late old-runtime completion/poll race
from failing the restarted runtime with SQLITE_BUSY. Terminal key outcomes commit with invocation state on SQLite/PostgreSQL.

This earlier business-key delivery alone did not establish separate-attempt or
effect-level guarantees. The attempt-journal increment below adds independent
permit records; effect-level guarantees remain outstanding. Automatic rebind,
mandatory-key tool policy, argument keys, TENANT sharing, dedupe windows and
retention/tombstone jobs remain outstanding. Historical native transport keys are
not backfilled: the operator guide requires a coordinated cutover and settlement
of legacy workflows on their original invocation IDs. That business-key increment left unkeyed calls on their existing behavior; the
newer journal below also fences their consumed attempts. This earlier delivery
did not satisfy the complete first milestone.

Executed validation for the business-key delivery at `2dacd836`:

- `EnterpriseOperationTest`: **43 passed, no skips**, including a concurrent
  reservation/replay/unknown-outcome case through a disposable real PostgreSQL
  runtime-role fixture; the other cases use SQLite. Includes concurrent reservation, new request IDs/reopen,
  mismatched input/owner, retention expiry, current-policy refusal, ambiguous
  downstream retry, expired unconsumed binding, denied-admission rollback and
  canonical-operation cancellation ownership.
- Java SDK runtime: **100 passed**, including restarted-runtime recovery with a
  late old-runtime completion. The initial SQLite lock failure was reproduced
  and fixed with immediate writer acquisition; no debug logging was retained.
- `python tools/check.py --ci-common --ci-part rust`: **passed** workspace format,
  tests and Clippy. Gateway includes native-key stability and modern MCP metadata
  replay/malformed-input tests.
- Contract suite: **132 passed, 2 PostgreSQL-only skips (134 total)**; generated bindings
  verified with `python tools/contracts/generate.py --check`. The separate real
  PostgreSQL Java business-key check above executed without skips.

- Final `./gradlew --no-parallel --max-workers=2 javaCheck`: **passed** with a
  fresh isolated PostgreSQL fixture after the SQLite fix and corrected expiry
  fixture. Control's full enterprise suite: **162 passed, no skips**. Control's
  main suite: **259 passed, 5 configured skips (264 total)**. The required Java
  module checks completed successfully.

Source SHA-256: `8502890893a9f40c43417b0b8349fb78cdd820151988579a45b84d9f34282948` (344 files). Digest input is the sorted POSIX
relative path, one NUL byte, then file bytes for `.java`, `.rs`, `.json`, `.sql`
and `.py` files recursively under `apps/control-plane/src`, `apps/gateway/src`,
`apps/gateway/tests`, `packages/contracts/schemas`, `tools/contracts` and
`tool-sdk/java-sdk/runtime-mcp/src/main/java`. Documentation is excluded so this
record can be committed with the tested code without a self-referential hash.

## Operation/attempt journal delivery

[ADR 035](../adr/035-operation-attempt-journal.md) and the
[operator guide](../operations/operation-attempt-journal.md) define the V3 foundation.
Every admission has an operation record. Every issued execution permit has an immutable
attempt identity and independent append-only snapshots; late executor evidence
remains associated with its original nonce. V3 backfills retained permits without
inventing historical snapshots or promoting old success to verified finality.

Replacement requires Control's existing expired, never-consumed nonce and attempt
binding, within the original invocation deadline. Database guards refuse live,
consumed, missing or unbound execution predecessors and prohibit consumption resets.
The unkeyed legacy DOWNSTREAM_IDEMPOTENCY requeue path is also removed.

The internal summary aggregates current execution/consumption evidence:
RUNNING takes precedence while execution is active; consumed terminal work stays
OUTCOME_UNKNOWN. A new, completely journaled operation can become final FAILED
only without ever consuming a permit. Full manifest/effect finalization and caller
presentation are not claimed; legacy invocation/business-key outcomes retain their
existing executor/reconciler grade. This increment does not finish phase 1 or the
first vertical-slice acceptance milestone.

Executed validation for the operation-journal delivery at `e34042a0`:

- Final focused real-PostgreSQL run: **59 enterprise tests and 18 main tests
  passed, no skips**. Covers attempt history/reopen, expired unconsumed permit
  replacement, stale-permit refusal, conservative operation grades, historical
  backfill, forced tenant RLS/privilege catalog, nonce reset/retry denials, existing
  service permit/TLS/vault compatibility and late-evidence association.
- Full contract suite: **134 passed, 2 configured PostgreSQL-only skips (136
  total)**. PostgreSQL behavior is exercised separately through Java's isolated
  runtime-role fixture. Final SQLite journal/nonce-guard and prospective-migration
  tests were also rerun after the nonce-purpose distinction.
- Complete `./gradlew --no-parallel --max-workers=2 javaCheck`: **passed** with
  an isolated real PostgreSQL fixture. Full enterprise suite: **166 passed, no
  skips**; main Control suite: **259 passed, 5 configured skips (264 total)**.
- After adding the per-operation nonce lookup index, the final migration/runtime
  selection was rerun against another fresh PostgreSQL fixture: **59 enterprise
  and 18 main tests passed, no skips**. SQLite journal and prospective-migration
  checks also passed against the final indexed migration.

Source SHA-256: `d4b1e18e3b99a6c8dd5613634994f53727bac1834f7f1a2fba20ad9bcd0ddd4a` (271 files). Hash input is each sorted POSIX
relative path, a NUL byte and its file bytes, for `.java`, `.sql` and `.json` files
under `apps/control-plane/src`, plus
`tests/contracts/test_operation_attempt_storage.py`. Documentation is excluded.
No current-commit SHA is invented; this source record travels with its code.

## Effect-generation foundation delivery

[ADR 036](../adr/036-effect-authorization-generations.md) and the
[operator guide](../operations/effect-generations.md) define this scope. V4
persists immutable effect identity/request binding and authorization generations.
Only released generations can reopen; owner, nonce, current-generation and
single-send guards prevent stale dispatch/consumption. Expiry without release is
ambiguous. Consumed work remains pending; broker observations cannot settle it,
and conflicting target outcomes are retained without reauthorizing.

The live integration is the existing OLO service write-permit path, with the
original service body/resource fingerprint and legacy owner identifiers. General
HTTP binding, broker APIs, target shared claims and consent remain outstanding.
This increment does not establish a target deduplication guarantee or satisfy a
complete phase/first-milestone exit criterion.

Executed validation for the effect-generation delivery at `3f03acaf`:

- Complete `./gradlew --no-parallel --max-workers=2 javaCheck`: **passed** with
  an isolated real PostgreSQL fixture. **174 enterprise tests passed without
  skips**; main Control suite: **259 passed, 5 configured skips (264 total)**.
- Eight effect-ledger tests include a real PostgreSQL runtime-role exercise:
  generation release/reopen, stale nonce refusal, one concurrent sender, owner
  and request mismatches, rollback, timeout ambiguity, late outcomes, broker
  evidence separation and conflicting target receipts. Existing OLO service
  bridge/TLS tests verify the live atomic issue/consume integration.
- Two final shipped-SQLite effect-generation storage tests passed; prospective
  migration sequencing/SQLite scenarios passed (one PostgreSQL-only specification
  check skipped; PostgreSQL is exercised separately by Java).

Source SHA-256: `6799e849d8374062d84e27501baf9c83dc0f33897af080eb5b72b6f09c881b52` (276 files): sorted POSIX relative path,
NUL, then bytes of `.java`, `.sql`, `.json` files under `apps/control-plane/src`
and `tests/contracts/test_effect_generation_storage.py`. Documentation is excluded.

## Prepared HTTP request delivery

[ADR 037](../adr/037-prepared-http-request-binding.md) and the
[operator guide](../operations/prepared-http-requests.md) describe one immutable
buffered request, normalized on the wire before JCS fingerprinting. It binds the
pinned service profile, route, actual URL, body bytes and non-excluded headers.
Declared credential values are excluded while their names/auth ID remain bound.
Production egress disables redirects and automatic protocol retries; tools cannot
override broker transport headers or exploit case-colliding duplicate headers.

These fingerprints remain broker observations: Control effect-grant binding and
target verification are the next task, not a claim of this delivery. No complete
phase or first-milestone acceptance criterion is marked complete.

Executed validation:

- `python tools/check.py --ci-common --ci-part rust`: **passed** workspace
  formatting, tests and Clippy. After materializing reqwest's implicit Accept
  header in the fingerprint, the affected Tool Host suite and Clippy were rerun
  and passed against the final request preparation.
- Shared digest vectors passed in both Tool Host and Control's
  `HttpRequestBindingVectorTest`. Tests bind body/recipient changes and duplicate
  query ordering, preserve credential-value exclusion, reject ambiguous content
  types, and verify one request on an ambiguous TCP disconnect after its body.
- Real rootless containment gate: **6 executed tests passed** on x86_64 using the
  prepared Python image `docker.io/library/python@sha256:aa943ce411423c7582288f18707ff66eddcbaeb510502d747a72aeb34f30d159` and I/O device `8:48`.
  The report under `build/security/sandbox/conformance.json` records parent
  revision `3f03acaf6077855600805a73c80f4aa864d44528` plus working-tree source SHA-256
  `e1f5807ff47408b64a1424ce418f4134bf11bcb46ec860c7e8d274df751d2e62`. That snapshot precedes this validation-text append;
  the tested code was unchanged. This qualifies the Python containment slice,
  not generalized effect authorization or target deduplication.

Final code/fixture SHA-256: `42de6a136704e9a612be9263ab3184fdbaa28217b586a45ad916f752832cbde0` (31 files): sorted POSIX
relative path, NUL, then bytes for Rust files under `apps/tool-host/src` and
`apps/tool-host/tests`, the shared JSON vector fixture and the Control Java vector
test. Documentation is excluded. HTTP/2 GOAWAY and full target/consent conformance
remain outstanding; no whole phase is marked complete.
