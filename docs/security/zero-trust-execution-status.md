<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Zero Trust Execution implementation status

The [revision-10 plan](../plans/zero-trust-tool-execution.md) is the preserved
proposal linked by issue #230. A merged design or an authored conformance case
does not establish a passing security claim.

## Overall task progress

Updated: **2026-11-01**. Branch: `feature/zero-trust-execution-plan`.
Previous containment delivery: [`0813b71b`](https://github.com/olo-labs/olo-toolgate/commit/0813b71bbe2a0f9276a1b2e2260b25454a31d3d4).

**Overall: partially implemented.** Fifteen scoped deliveries are implemented:
renewable leases/durable execution recovery, qualified Python OCI containment,
explicit business-key binding, independent operation/permit-attempt journaling, an effect-generation foundation, exact broker transport preparation (explicitly restricted to HTTP/1.1), OLO service HTTP fingerprint grants, independent Control validation of their pinned manifests, actual knowledge-target transport integration, owner-bound OLO single-dispatch gates, a shared SQL target-claim foundation, OLO claim-token consumption/status/outcome APIs, verified target-claim orchestration with memory/RAG transaction integration, source-bound actual Control/Gateway/target outcome qualification, and adoption of the complete A1–A9/C1–C8 security model. Executed validation for the current
delivery is recorded below. **0 of 7 full roadmap phases have met every exit
criterion.** Phases 0–3 have
partial delivery; phases 4–6 have not started as generalized implementations.
**Weighted delivery progress: 23.7% complete; 76.3% pending.** This planning metric credits only completed delivery stages, not partially completed stages. It is separate from full-phase security acceptance (still 0 of 7). See the baseline below.

The roadmap defines **7 major phases**, not a fixed number of incremental
deliveries. Its original per-phase estimates sum to approximately **35–44
weeks for a sequential phase schedule**; phases 2 and 3 may overlap. These are
the proposal's full-plan estimates, not a measured remaining effort or calendar
completion forecast. The midpoint of each original phase estimate supplies the fixed planning weight below; this is not a measured remaining-effort forecast.

| Plan phase | Status | Delivered | Remaining before full phase completion |
|---|---|---|---|
| 0 — Boundary and harness | Partial | Weighted delivery baseline and one-commit stage workflow; source-bound actual stack gate; adopted A1–A9/C1–C8 threat model across security docs; preserved revision-10 plan, claim inventory and executable OCI conformance gate with revision/source/image evidence. | Execute all C1–C8 conformance cases and publish a complete release scorecard. |
| 1 — Operations, leases and evidence | Partial | Durable device/fence-bound leases; heartbeat/watchdog; admission spool; result outbox; immutable late executor evidence; SQLite/PostgreSQL migrations and tests; conservative business-key binding; V3 operation/attempt records, immutable snapshots, historical backfill, positive-proof reservation replacement and conservative operation summaries. | Generalized requiresBusinessKey/namespace/dedupe-window policy, retention maintenance, whole-invocation rebinding and full effect-aware operation finalization/caller integration; complete admission/rebinding and crash conformance. |
| 2 — Containment | Partial; Python worker slice delivered | Rootless Podman/crun workers; permit-bound image; isolated namespaces and UID ranges; read-only files; enforced resource limits; stdio broker; teardown/restart recovery; no native fallback; service unit and CI gate. | Separate the trusted broker's OS identity/network namespace as specified by the plan; broader deployment/interface conformance and deployment SLO evidence. Other languages and VM tiers require separate qualification. |
| 3 — Effect authorization and recovery | Partial; ledger foundation | Durable immutable effect identities; authorization generations/owners; release/send/consume/ambiguity states; target-vs-broker evidence; timeout and terminal-invocation abandonment. Integrated with existing OLO write permits. Broker-owned prepared HTTP requests now have versioned fingerprints and explicit no-retry HTTP/1.1 transport. OLO service permits sign those digests and require target recomputation before consumption. Leased v2 issuance independently checks signed admission and pinned route/profile manifests. The actual memory/RAG listener integration supplies accepted HTTP facts using trusted profile configuration and refuses unbound downgrade. Bound OLO writes require a recorded process/handler owner and a single authenticated AUTHORIZED-to-SENDING transition before dispatch. A target-owned JDBC claim foundation now supports unique ownership, apply/recovery CAS and atomic business completion; the actual memory/RAG adapters now integrate it. Bound write claims now sign effect identity/generation; dedicated service APIs return consumption status and retain authenticated late target outcomes. The target SDK separates verified facts from online consumption, persists APPLYING before external actions, and atomically journals completion/outcome delivery with business SQL. The owned actual Control/Gateway fixture now verifies committed memory/RAG outcome settlement against Control records. | Generalize the independently validated OLO path to broker enforcement for all route classes, effect keys/manifests, generalized owner protocol, consent slots, generalized target fencing/deduplication, reconciliation and operation finalization. |
| 4 — Evidence presentation and tamper evidence | Not started | Raw executor evidence is retained by phase 1. | Outcome grades in presentation/exports, tenant audit chains, signed external anchors and offline verification. |
| 5 — Identity hardening | Not started | Existing device certificates remain the baseline. | Sender-constrained agent-run credentials, narrowing delegation and continuously verified executor posture. |
| 6 — Policy differentiation | Not started | Existing approvals remain the baseline. | Broker OAuth/token exchange, trusted provenance, exact effect previews and stronger step-up approvals. |

### First vertical-slice acceptance

The complete first milestone in issue #230 is **not yet accepted**.
S03 adopts the [threat model and claim definitions](zero-trust-boundaries.md);
documentation alignment supplies no additional runtime acceptance. All acceptance
items below retain their existing implementation status.

| Acceptance item | Status | Evidence / outstanding work |
|---|---|---|
| Exact consent rejects recipient changes and additional effects | Not implemented | OLO fingerprints are independently route/profile-checked by Control and signed/target-checked, including actual memory/RAG component TLS tests; independent exact consent slots and additional-effect rejection remain unimplemented. |
| Revocation refuses subsequent sensitive effects and stops healthy workers | Partial | Lease revocation tears down a real OCI SDK worker; generalized sensitive-effect authorization and measured deployment bounds remain outstanding. |
| Crashes around dispatch/target commit preserve evidence and prevent unsafe retries | Partial | Admission/result recovery, database reopen, immutable attempt snapshots and refusal of consumed-attempt replay are tested; TLS tests verify HTTP/1.1-only dispatch and target refusal of changed transport facts before storage; owner races permit one SENDING winner and refused gates contact no target; actual target tests now exercise paused-owner abandonment, two JVMs, writer loss after business commit and lost outcome acknowledgement; complete Control/Gateway crash scenarios remain outstanding. |
| Partition, duplicates and concurrent target replicas exercise fencing/recovery | Partial | Host Control-partition and OCI recovery tests pass; actual knowledge component storage/TLS/vector qualification is now available; shared SQLite/PostgreSQL claims and actual memory/RAG JVM replicas now exercise one mutation, paused-owner fencing, writer loss and outcome redelivery; actual Control/Gateway normal-path settlement now passes; complete partition/crash qualification remains outstanding. |
| Operation finalization includes all attempts/effects and late evidence | Partial | Each permit has independent identity/snapshots and late evidence. New operations finalize FAILED only without consumption; consumed terminal work remains unknown. Authenticated target outcomes now retain late completion/conflict evidence independently of permit expiry or signing-key rotation. Complete effect aggregation and caller-visible finalization remain outstanding. |
| Revision-bound evidence distinguishes fenced/unfenced guarantees | Partial | OCI report for `0813b71b` records six executed tests, source digest, image and architecture. Target component evidence now pins verified SDK/contracts artifacts and executes replica/crash cases; actual Control/Gateway development qualification now passes; deployment guarantees and the complete release scorecard remain outstanding. |

**Next implementation stage:** S04, complete the release conformance scorecard.
S03 threat-matrix/security-document adoption is complete. Continue in stage order;
dependencies may be implemented inside a stage but cannot earn duplicate credit.
S17 development-stack qualification is complete; generalized grants, exact consent,
operation finalization and production deployment evidence remain outstanding.

### Weighted delivery baseline (2026-11-01)

This is a prospective planning model, not a measured fraction of engineering hours
or a security assurance percentage. Phase weights use the original estimate
midpoints: 2 + 7 + 7 + 7 + 3.5 + 6 + 7 = **39.5 planning weeks**.
Each phase's weight is split equally among its explicitly defined delivery stages.
Equal weighting within a phase is a planning assumption, not observed effort.
A stage earns its entire weight only when its stated scope and executed validation
are complete; Partial and Pending earn zero. Existing work is credited only for
the bounded Done stages below, not for the complete parent phase.

**Completed weight = 1.5 + 3.5 + 1.75 + 2.625 = 9.375.**
**Completion = 100 × 9.375 / 39.5 = 23.734…%, rounded to 23.7%.**
Pending weight is 30.125, or 76.3%. Ten of 33 delivery stages are Done;
stage count is not the percentage formula. No phase receives full acceptance
until all its original exit criteria have executed evidence.

| Roadmap phase | Planning weight (weeks) | Stages done / total | Earned weight | Weighted contribution |
|---|---:|---:|---:|---:|
| 0 | 2 | 3 / 4 | 1.5 | 3.80% |
| 1 | 7 | 3 / 6 | 3.5 | 8.86% |
| 2 | 7 | 1 / 4 | 1.75 | 4.43% |
| 3 | 7 | 3 / 8 | 2.625 | 6.65% |
| 4 | 3.5 | 0 / 4 | 0 | 0.00% |
| 5 | 6 | 0 / 3 | 0 | 0.00% |
| 6 | 7 | 0 / 4 | 0 | 0.00% |

Do not silently change the denominator to improve the score. If scope changes,
record the old/new baseline, rationale and recalculated score in the same commit.
Future percentages use this formula and the evidence-backed stage statuses.

| Stage | Phase | Delivery scope | Status | Completion criteria / evidence |
|---|---:|---|---|---|
| S01 | 0 | Plan and claim inventory | Done | Preserved revision-10 plan and authoritative claim inventory. |
| S02 | 0 | Revision-bound OCI qualification runner | Done | Six executed OCI cases; source/image/revision evidence. |
| S03 | 0 | Complete threat matrix and security documentation | Done | Adopted A1–A9/C1–C8 model and aligned security/trust/custody/endpoint/OLO documentation; structure/local-link checks and preserved-plan check passed. |
| S04 | 0 | Complete release conformance scorecard | Partial | CI executes all required C1–C8 cases and publishes revision-bound failures/results; close boundary/harness phase criteria. |
| S05 | 1 | Renewable execution leases | Done | Lease, heartbeat, watchdog, revocation and expiry tests. |
| S06 | 1 | Durable admission and evidence delivery | Done | Admission spool, result outbox and immutable late evidence; recovery tests. |
| S07 | 1 | Independent attempt journal | Done | V3 attempts, immutable snapshots and historical backfill tests. |
| S08 | 1 | Generalized business-key policy | Partial | requiresBusinessKey, namespace, dedupe-window, retention/tombstone maintenance and migration tests. |
| S09 | 1 | Whole-invocation rebinding | Partial | Positive proof, original deadline and safe qualified broker-only replacement; duplicate/crash conformance. |
| S10 | 1 | Complete operation finalization and callers | Partial | Aggregate every attempt/effect and late evidence; caller history/presentation and transitions/audit-spool/duplicate-execution acceptance. |
| S11 | 2 | Qualified Python worker containment | Done | Rootless OCI namespaces, filesystem, resources, no fallback and teardown/recovery tests. |
| S12 | 2 | Trusted broker OS/network separation | Pending | Separate broker identity/network namespace; prove worker cannot acquire broker authority. |
| S13 | 2 | Complete containment/interface conformance | Partial | Full sandbox, egress and interface-attack matrix for the declared Linux tier; explicitly gate other runtime tiers. |
| S14 | 2 | Containment deployment qualification | Partial | Deployment lifecycle, cleanup, restart and measured deployment bounds for qualified pools. |
| S15 | 3 | Durable effect-generation ledger | Done | Immutable effects, generation/owner transitions, conflict and timeout tests. |
| S16 | 3 | Immutable prepared HTTP transport | Done | Canonical request fingerprints, HTTP/1.1 no-retry transport and TLS refusal tests. |
| S17 | 3 | Fenced OLO memory/RAG integration | Done | Independent manifests, owner dispatch, shared SQL claims, authenticated outcomes, actual JVM crash/replica tests and real Control/Gateway development-stack settlement. |
| S18 | 3 | Generalized effect grants and owner protocol | Partial | All supported route classes use independently validated manifests/intent, full request binding and owner ordering; no unbound bypass. |
| S19 | 3 | Exact consent effect slots | Pending | Approved exact effects/slots; changed recipient/body and additional effects refused. |
| S20 | 3 | Per-effect keys, safe resend and reconciliation | Pending | Approved targetDedupeGuarantee only; history-aware generations, downstream effect keys and reconciliation adapters; ambiguous outcomes never unsafe retries. |
| S21 | 3 | Generalized target fencing SDK/adapters | Partial | Generalize OLO claims to declared fenced target classes; shared claim ownership and recovery/crash conformance. |
| S22 | 3 | Revocation, dispatch and phase-3 acceptance | Partial | Push/long-poll kill, inbound mTLS dispatch, connectivity-class SLOs, kill-* and full-stack partition/crash cases; phase-3 exit matrix including operation aggregation from S10. |
| S23 | 4 | Outcome grades and honest presentation | Pending | Console, exports and analytics distinguish fenced/unfenced/unknown evidence. |
| S24 | 4 | Tenant audit hash chains | Pending | Ordered tenant chains with integrity, migration and tamper tests. |
| S25 | 4 | Signed external anchors | Pending | Signed chain heads, external anchoring and key lifecycle tests. |
| S26 | 4 | Offline audit verification | Pending | toolgate audit verify; independent tamper/truncation/anchor verification and phase acceptance. |
| S27 | 5 | Sender-constrained run identity | Pending | Short-lived agent-run credentials bound to proof of possession; stolen credential replay refused. |
| S28 | 5 | Narrowing delegation | Pending | Verified delegation chains; scope amplification and replay refused; delegation conformance. |
| S29 | 5 | Continuously verified executor posture | Pending | Attestation verifiers feed ControlDeviceEvidence and lease renewal rechecks; identity/posture phase acceptance. |
| S30 | 6 | Broker OAuth and token exchange | Pending | Broker-owned scoped token exchange; no worker token authority; lifecycle/refusal tests. |
| S31 | 6 | Trusted provenance policy | Pending | Authenticated provenance and policy decisions; injected/untrusted input refusal tests. |
| S32 | 6 | Exact effect preview | Pending | Reviewer preview binds the canonical authorized effect; mutation invalidates approval. |
| S33 | 6 | Stronger step-up approvals and final acceptance | Pending | Step-up binds identity/effect/deadline; flagship demo, external red-team exercise and complete release/phase acceptance. |

### One commit per completed stage

Within each stage, implement a small focused change, run the focused tests,
fix any failures and rerun the affected tests. Repeat this change/test/fix loop
for the next small change until the complete stage scope is implemented.
Then run thorough stage-level validation: all applicable component, integration,
security/conformance, migration and regression checks. Fix failures, repeat the
affected checks, and broaden validation when a fix changes the risk or scope.
Do not repeatedly run unrelated full suites after every small edit.
Only after the entire stage and its final validation pass, commit. Include implementation, contracts/migrations, tests, operator
documentation, these progress tables and affected security claims in that same
commit. Use a subject such as `security(S03): complete threat-matrix adoption`.
Push that completed stage to `feature/zero-trust-execution-plan` before taking
the next outstanding stage. Do not push intermediate implementation commits or
a separate progress-only follow-up for that stage. Existing history stays intact.

A delivery stage is smaller than a roadmap phase; this rule does not rename
partial phases as complete. For changes spanning repositories, Git necessarily
requires one stage commit **per affected repository**; cross-reference the
known tested revisions/source digests and push both before recording delivery.
The current qualification-stage commit also establishes this requested baseline.
If a stage cannot meet its criteria, keep it Partial and do not award its weight.

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
| C3b, current per-effect authorization | Partial; OLO write path only | Existing OLO current-authority checks now reserve/consume durable effect generations atomically. OLO permits also sign prepared HTTP fingerprints and the target verifier checks accepted-request facts. Control now independently verifies pinned OLO profile/route manifests and original signed admission scope; v2 leases cannot downgrade to unbound issuance. Actual memory/RAG listeners supply HTTP facts from accepted requests with trusted deployment bindings. Bound write dispatch now rechecks current authority and recorded ownership at a single SENDING transition; bound target consumption refuses unsent generations. A shared SQL claim-store foundation is tested independently. Control now exposes claim-token consumption/status and late authenticated outcomes; actual memory/RAG integration now uses verified facts, durable claims and a target outcome outbox. Component crash cases use scripted Control; the additional owned development fixture now joins actual Control outcome records to committed target claims. Production deployment evidence remains outstanding. Generalized enforcement, exact consent and complete stack/deployment conformance remain required. |
| C3c, bounded unmediated execution | Partial | Native direct-worker supervision and OCI teardown on cancellation/deadline are tested. Enforced service cgroups contain worker processes; measured deployment revocation SLOs remain required. |
| C4, bound admission intent | Implemented existing boundary | Tool Host admission binds signature, audience, arguments and content digests; dispatch integration tests. |
| C5, contained execution | Available for qualified Python OCI pools | Executed rootless namespace, UID, filesystem, socket, syscall, resource, flooding, cancellation and recovery tests. Requires the documented delegated service; other runtime tiers do not inherit the claim. |
| C6, honest tamper-evident record | Partial | Durable admission, immutable executor evidence, independent immutable attempt snapshots and host result retry; standalone SDK SQLite restart/late-completion recovery uses immediate writer transactions. Hash chains and external anchors remain required. |
| C7, revocation | Partial | Renewable leases and current authority checks; full target and compromised-host distinctions remain required. |
| C8, operation/effect integrity | Partial | Single-use admission and durable explicit operation keys exist; keyed unknown outcomes cannot reopen via legacy resend. Independent permit attempts and positive-proof reservation replacement are journaled; all consumed same-row replay is disabled. Effect generations now separate authorization from consumption/outcome on the OLO write path. The HTTP/1.1 broker now binds OLO service fingerprints through signed claims, immutable effect identity and target checks. Pinned OLO request/route validation is independent at Control. A process/handler-owned single-send gate prevents replaying a bound generation's dispatch authority. The SQL target claim foundation tests unique ownership and atomic business completion. Claim-token consumption/status and late target outcomes are exposed at Control, and actual memory/RAG transactions now adopt them with restricted SQL state/binding guards and durable outcome delivery. Generalized key policy, other route classes, consent slots, generalized target deduplication and full operation finalization remain required. |

The adopted [A1–A9 matrix and C1–C8 definitions](zero-trust-boundaries.md)
now apply across the security documentation. S03 corrects claim scope, especially
C2's sender constraint, C3b's effect ordering, C3c/C7's healthy-host limitation,
C5's qualified runtime scope, C6's missing anchors and C8's ambiguous-retry rule;
it changes no runtime guarantee or claim implementation status.

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

At delivery `44795e74`, these fingerprints were broker observations. The OLO
service path now binds/verifies them as recorded below; generic routes remain
broker observations without a generalized grant. No complete
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

## Explicit HTTP/1.1 transport qualification

The prepared-request delivery was pushed at known revision `44795e74`.
This follow-up explicitly restricts the production egress client to HTTP/1.1,
including future reqwest feature unification. A real CA-verified TLS integration
test offers HTTP/2 first, requires negotiated http/1.1, and verifies the actual
POST request and body and successful response. HTTP/2-only targets are unsupported;
HTTP/2 GOAWAY recovery is not claimed or qualified by this restriction.


Executed validation:
- The complete standard Tool Host test suite and all-target Clippy with
  warnings denied passed. Privileged rootless tests remain ignored in this
  standard run; the prior six-test containment qualification is recorded above.
- The real TLS test passed both with the shipped dependency graph and with
  reqwest's HTTP/2 feature explicitly enabled for the test.
- A negative control temporarily removed `.http1_only()` while enabling
  HTTP/2: the test failed because the server negotiated h2. The production
  restriction and original lockfile were restored; no dependency change ships.
- `cargo fmt --all -- --check` and `git diff --check` passed.

Tested code SHA-256: `9247c1d22e2d3862dae10cfb8e56a94442adc27d829eb2dc84de3ba782bf288f`:
sorted relative path, NUL, then bytes of `apps/tool-host/src/egress.rs` and
`apps/tool-host/tests/egress_protocol.rs`; documentation excluded.
Generalized Control fingerprint grants, exact consent, target shared claims and
full roadmap exit criteria remain outstanding; scoped OLO binding is recorded below. The next implementation target is unchanged.


## OLO prepared HTTP fingerprint grants

[ADR 038](../adr/038-olo-http-request-grants.md) extends the existing service
permit path. The broker prepares one immutable request before issuance, sends its
digest to authenticated Control and then injects only the excluded service
credential. Control signs `httpRequestDigest`, includes it in the effect's
immutable request binding and checks it during consumption. Reissuing the same
write with a changed digest or omitting a previously bound digest is refused.

The target library computes the same version-1 fingerprint from trusted routing
configuration plus accepted request URI, method, repeated headers and exact body.
A bound permit without transport facts or with a changed fingerprint is refused
before online consumption. Existing operation/resource/body/mTLS/current-authority
checks remain required. New optional contracts retain legacy unbound permits;
new brokers request bound permits and require upgraded target integrations.
Upgrade Control and target integrations before the broker; generated Java source
consumers must recompile for the additional optional constructor member.

At `23d82514`, this OLO-path delivery accepted the digest as a broker assertion.
Independent Control manifest validation is delivered below. Neither increment
establishes the generalized broker effect API or completes phase 3.
Exact-consent slots, shared target claims/deduplication, independent broker-owner
protocol and effect-aware finalization remain outstanding. The original protocol-library delivery did not include actual applications;
knowledge-target component integration is now recorded below.

Executed validation (known parent revision `4185d9d5f7f4f86828c7a140c66af469fb86005c`):
- `./gradlew --no-parallel --max-workers=2 javaCheck` passed with an isolated
  real PostgreSQL fixture. Control enterprise: **176 passed, no skips**.
  Main Control: **260 passed, 5 configured skips (265 total)**.
- Final `:service-authorization:check`: **17 passed, no skips**, including
  three fingerprint tests and fourteen permit-verifier tests. The root
  `javaCheck` now depends on this library's check; the updated task graph was
  verified with `javaCheck --dry-run` after separately executing the check.
- The TLS storage test reads actual HTTP bytes over accepted mTLS, refuses
  changed recipient headers before consumption, then commits one authorized
  effect to a separate SQLite store. Bridge tests cover malformed, changed and
  omitted digests, replay, no downgrade, rollback and revocation.
- `python tools/check.py --ci-common --ci-part rust` passed workspace formatting,
  tests and warnings-denied Clippy. The immutable prepared-request test verifies
  only the declared service credential can be added after fingerprinting.
- Contract generation and fixture drift checks passed. Python contract regression
  suite: **136 passed, 2 PostgreSQL-only checks skipped (138 total)**; the Java
  gate separately exercised the real PostgreSQL runtime and migration path.
  TypeScript check/build and both roundtrips passed; PHP 8.2 container roundtrips
  covered **278 v1 and 250 v2 models**, plus syntax checks for the changed models.
- `tools/quality.py` and `git diff --check` passed.
- Real rootless gate: **six executed tests passed**, image `docker.io/library/python@sha256:aa943ce411423c7582288f18707ff66eddcbaeb510502d747a72aeb34f30d159`,
  architecture `x86_64`, I/O device `8:80`.
  Its report records source SHA-256 `f51439b6333d0072f0d1caddeaffabf0665109965a62dea6e04c2046f82f422b`.
  That snapshot precedes the verification-task dependency and validation-text
  append; the tested runtime source is unchanged. This remains a Python
  containment qualification, not target deduplication or whole-phase acceptance.

Final code/fixture SHA-256: `845ca28d7ea89095e247ad7f07d8e73590940ec67a666253593ab1bed629dfae` (24 files):
sorted POSIX relative path, NUL, then bytes of changed/new `.rs`, `.java`,
`.kts`, `.json`, `.php`, `.ts` files plus
`tests/fixtures/security/http-request-binding-v1.json`. Documentation is excluded.
No full roadmap phase or first-slice acceptance criterion is marked complete.


## Independent Control validation of pinned OLO manifests

[ADR 039](../adr/039-control-http-manifest-validation.md) closes the broker-assertion
gap for leased v2 OLO issuance. The broker submits the credential-free manifest
and original signed invocation permit. Control verifies its own purpose-bound
signature, tenant/device/identity/tool/package/argument/resource/fence binding,
live execution lease and original deadline, then loads the immutable service
profile by the signed canonical digest and checks its revision and granted routes.

Control independently enforces brokered HTTPS destination host/port, endpoint and
method, bounded path templates, query allowlist and canonical ordering/encoding,
media type, allowed semantic headers, credential-exclusion metadata and admitted
body digest. It recomputes the complete version-1 HTTP digest. Leased v2
executions cannot bypass validation by requesting an unbound legacy permit.
An expired short admission window alone does not stop an already consumed
invocation whose current lease/authority and original execution deadline remain live.

Digest-only bound clients must upgrade with Control. Legacy unbound issuance is
retained only without a v2 execution lease. Body byte limits remain a broker/target
check because a digest cannot prove body length. Whole-segment path variables
and bounded linear character-class constraints are supported; complex patterns
and embedded/ambiguous placeholders fail closed. Control does not equate a
route allowlist with exact caller consent.

Generalized route classes, independent broker owner/handler grants, exact consent
slots, shared target claims/deduplication, effect-aware finalization and roadmap
phases 4–6 remain outstanding. This checkpoint does not complete the roadmap.

This checkpoint also removes the lower HTTP client's canceled-request retry
path by disabling idle HTTP/1 pooling. A real TLS test keeps the first socket
alive and requires a fresh connection for the second write; restoring pooling
fails the negative control. The broker still disables protocol retries and
redirects. Connection reuse is traded for the qualified no-replay behavior.

Admission JCS and legacy exact-body JSON digests are kept in their separate
domains; the consumed signed admission nonce binds the original arguments,
while the manifest body digest is checked against the stored service body
fingerprint. A numeric `1.0` regression covers that distinction. The existing
user-credential forgery test now flips a decoded signature byte instead of
occasionally replacing an already-equal character.

Executed validation, known parent `23d8251453474f20c5fa1944fd36522498019fa4`:
- Final `./gradlew --no-parallel --max-workers=2 javaCheck` passed with an
  isolated real PostgreSQL fixture: **182 enterprise tests passed, no skips**;
  main Control **260 passed, 5 configured skips (265 total)**.
  An earlier run exposed the nondeterministic no-op forgery mutation described
  above; the corrected negative and the full rerun passed.
- The service bridge/TLS suite covers recomputed-but-unauthorized manifest
  mutations, forged/missing/cross-invocation proofs, signed revision/route scope,
  pinned-document integrity, downgrade refusal before first issuance, live lease
  after admission expiry, bounded template patterns and separate digest domains.
- Final `python tools/check.py --ci-common --ci-part rust` passed formatting,
  workspace tests and warnings-denied Clippy. The real TLS transport test requires
  two fresh HTTP/1 connections while keeping the first socket alive; temporarily
  restoring pooling makes that test fail. The no-pooling production code was restored.
- Canonical generation/fixture drift checks passed. Python contract suite:
  **136 passed, 2 PostgreSQL-only skips (138 total)**; PostgreSQL was exercised
  separately by Java. TypeScript check/build/roundtrips passed. PHP 8.2 container:
  **279 v1 and 250 v2 model roundtrips** and changed-model syntax checks passed.
- Source quality/license checks and `git diff --check` passed.
- Final real rootless containment gate: **six executed tests passed**,
  architecture `x86_64`, I/O device `8:80`,
  image `docker.io/library/python@sha256:aa943ce411423c7582288f18707ff66eddcbaeb510502d747a72aeb34f30d159`. Report source SHA-256:
  `d4874b86d37299c1aa1bc47c68611c56073bd74283c6fe8ad4031a7d359f05ac`. That snapshot precedes this validation-text append;
  tested runtime code is unchanged. This is containment evidence, not a full
  phase-3 or target-deduplication qualification.

Final code/fixture SHA-256: `14c93280768757ba31ebfeeef2566d9377b5d899f950feb6bd7a132dcdb11fe4` (27 files): sorted
POSIX relative path, NUL, then bytes of changed/new `.rs`, `.java`, `.kts`,
`.json`, `.php`, `.ts` files plus the shared
`tests/fixtures/security/http-request-binding-v1.json`. Documentation is excluded.
No full phase or first-milestone acceptance criterion is marked complete.


## Actual memory/RAG transport integration

The user identified olo-mono and olo-marketplace and requested a standalone
olo-knowledge checkout. The standalone repository was checked out
cleanly at dd5e42cd0045e697c04ec0483db7ac501fd6dfa2. The consolidated applications
and qualification scripts are in olo-mono/olo-knowledge, based on
59c53d78ce8b1523c0f957d3bcf2594f51a3bc8d; marketplace contains publishing code.
Implementation is on the matching feature/zero-trust-execution-plan branch in
olo-mono. The standalone checkout and marketplace remain unchanged.

[ADR 040](../adr/040-knowledge-target-http-bindings.md) connects the actual
KnowledgeServer listener to transport-aware target verification. A read-only,
closed trusted configuration supplies public origin, profile digest and route
map; accepted raw URI/method/repeated headers and body supply request facts.
Neither Host headers nor unverified claims select trusted profile metadata.
A transport-aware verifier now refuses an unbound legacy permit before online
consumption. Missing or malformed binding configuration fails service startup.

The service dependency and tool-package plugin upgrade to SDK 1.0.0-dev.1. The
owned stack fixture provisions packages and trusted profile bindings before
starting listeners. TLS tests use separate service JVMs, real accepted mTLS and
PostgreSQL; scripted Control supplies signed grants and consumption responses.
They reject changed Accept/query facts and unbound downgrade without consuming
the capability, then commit the authorized write once. Actual ACLs, receipt and
revocation checks remain active. Vector/corpus tests use real Qdrant and Ollama.

The component fixture now writes source/revision/SDK/image/model-bound counts,
refuses source changes during tests, clears stale passing evidence before
provisioning, refreshes working development artifacts and waits for the final
PostgreSQL TCP listener. The ToolGate entry point is
tools/security/knowledge.py, taking an explicit external component checkout and
Gradle 8.12 executable. Its report verifies exact SDK artifact and source matches.

This is component qualification with scripted Control, not a new full-stack
Control/Gateway/target run or production SLO acceptance. Generalized grants,
dispatch-owner protocol, exact consent, target shared claims, reconciliation,
effect-aware finalization and later roadmap phases remain outstanding.

Executed validation and delivery:
- olo-mono implementation and component evidence were committed/pushed together
  as [54d08504](https://github.com/olo-labs/olo-mono/commit/54d08504bdeec0fd5e62913b963c312e737a17bd)
  on feature/zero-trust-execution-plan. The measured working-tree source digest
  is dc45fad71d596a8db51f6b906e5c819611479dca711cc12324b41a03b513f160, based on known parent 59c53d78ce8b1523c0f957d3bcf2594f51a3bc8d.
- tools/security/knowledge.py executed the SDK publication and complete real
  component qualification, then verified source and artifact matches:
  **28 passed, no skips** (service-core 2, memory 10, RAG 16).
  The positive verify-only path passed; negative controls refused a skipped
  result and changed component source.
- Retrieval corpus: **200 documents, 150 queries**, recall@5 **1.0**,
  precision@5 **0.7333**, **0 unauthorized chunks**, **750 citations** and
  **0 citation errors**. This is service-component retrieval evidence; it does
  not establish full-stack latency or revocation SLOs.
- Exact SDK artifact SHA-256: 1007fb1878d376910d8578ff889d7287ec3d612d13eba8f556229d3c8081a35b.
  Verifier component SHA-256: 866a71f71a09895c2c10e1f568b1e56c073fba592c952ded3ddf6f4f7b55b24d.
  Reports retain actual PostgreSQL/Qdrant/Ollama image IDs and model digest.
- Full ./gradlew --no-parallel --max-workers=2 javaCheck passed with isolated
  PostgreSQL: **182 enterprise tests passed, no skips**; main Control
  **260 passed, 5 configured skips (265 total)**.
  Target library: **18 passed, no skips**.
- The memory and RAG packages built/packed with SDK 1.0.0-dev.1 and Gradle 9.7.1;
  both service installDist distributions built with Java 21 / Gradle 8.12.
  Source-header/license checks and git diff --check passed.

ToolGate known parent: 17b199e17005cb1b66878262fecea9714d028636. Final changed
code SHA-256: 0da4b13329072f200bf403b9454a963369f7b4046a8b1be86f63f325290900ab (3 files), sorted POSIX relative path,
NUL and bytes of changed/new .py/.java/.kts/.json files; documentation excluded.
The exact component source selector and image/SDK pins are retained in the
ignored build/security/knowledge/conformance.json report. No full roadmap phase
or first-slice acceptance criterion is marked complete.


## Owner-bound OLO dispatch delivery

[ADR 041](../adr/041-owned-olo-dispatch.md) adds random process-local broker
identities and fresh per-call handler identities. Bound issuance pins both;
different owners and SENDING issuance replay are refused. Direct device-mTLS
dispatch rechecks current authority and signed grant before one atomic
AUTHORIZED-to-SENDING transition. Bound target writes require SENDING.
A lost gate response grants no retry. Reserved OLO destinations retain verified
HTTPS even with the explicit development loopback option.

Focused validation passed: ServicePermitBridgeTest and
ServicePermitTlsQualificationTest; affected Rust tests and Clippy; the real mTLS
broker wire test proves refused gates contact no target and an allowed gate sends
once. Additional executed validation:
- Full javaCheck passed with a fresh real PostgreSQL fixture: Control enterprise
  184 passed, no skips; main 260 passed, five configured skips; service SDK
  18 passed, no skips.
- Python contracts: 136 passed, two configured PostgreSQL-only skips (138 total).
- Rust workspace formatting, tests and Clippy passed.
- TypeScript contract build and both tests passed; PHP 281 v1 and 250 v2 model
  round trips/security negatives passed.
- Generated bindings (1113 files), structural fixtures and UI operation bindings
  verified against their canonical sources.
- The real rootless OCI gate passed all six required cases on x86_64, backing
  device 8:80, with the immutable Python image already recorded above.
  Its report records known parent 0188778198bf710be152515c775a007c97a642ea and
  full source snapshot 58e573fb6583d1bd6e394afaad864de6e357fe63730f3fdeb532ae9bd5fedbea.

Changed source digest: 475e161e6c164892082b244cb67d73f46ee43d1c581f95457e680fa3536b1688.
Input is each sorted changed/new path beneath apps/ or packages/contracts/,
one NUL and exact file bytes (24 files); documentation is excluded. The rootless
snapshot includes the documentation before this evidence was appended.

This is an OLO write slice, not a completed phase. Generalized route grants,
release/reopen, consent slots, target shared claims, reconciliation and complete
operation finalization remain outstanding. Legacy unbound clients without v2
leases retain their prior behavior. Coordinate Control and broker upgrades.


## Shared target SQL claim foundation

[ADR 042](../adr/042-shared-target-claims.md) and the
[transaction guide](../operations/target-effect-claims.md) define a target-owned,
shared JDBC store. Only the unique claim insertion winner receives an owner
token. Entering APPLYING requires an owner/generation CAS; pre-apply recovery
advances the generation to ABANDONED and permanently fences a paused owner.
Recovery cannot abandon APPLYING. Business mutation and DONE/FAILED can share
the caller's transaction; no method commits, retries or deletes tombstones.

This foundation does not yet change OLO consumption, supply verified effect
metadata, publish target outcomes, or connect the memory/RAG transactions.
No fenced-with-deduplication target guarantee or full phase completion is claimed.
Next: bind target claims to verified grants and implement status-only Control
consumption replay/outcomes, followed by actual memory/RAG integration.

Known parent: 8c0d366d (owner-bound OLO dispatch). Changed source SHA-256:
8d791e33f3dbde3d63cc0545de87e117a1a59c782666338e05bb6ced9ccccc0e.
Input is the sorted four implementation/test/build/lock paths listed in this
delivery's diff, one NUL and exact bytes; documentation is excluded.
Executed targeted validation: service-authorization check passed with 25 tests
and no skips, including all seven JdbcEffectClaims tests. The PostgreSQL test
creates a fresh isolated target database and executes the same six scenarios as
SQLite: concurrent replicas with one committed business mutation, paused-owner
fencing, apply/recovery races, atomic rollback and completion after reopen,
uncertain APPLYING recovery, and binding/tenant/forged-owner refusal.
Full javaCheck also passed with a fresh isolated PostgreSQL fixture: Control
enterprise 184 passed without skips, main 260 passed with five configured skips,
and the service-authorization module 25 passed without skips. No Rust or wire
contract implementation changed in this foundation; their executed validation
remains the preceding known delivery.


## OLO claim-token protocol delivery

[ADR 043](../adr/043-olo-claim-consumption-outcomes.md) signs immutable effect
identity and generation in bound OLO write grants. First claim consumption
requires exact request facts, current authority and SENDING; nonce consumption
and recorded claim ownership commit atomically. Replays return status, never a
new execution capability; different tokens return CONSUMED_BY_OTHER_CLAIM.
Dedicated target status/outcome APIs bind the recorded audience/tenant/claim
without requiring the expired grant or its former signing key. Conflicting
target outcomes remain CONFLICT evidence. Legacy consumption still refuses repeats.

Actual memory/RAG target transactions have not adopted shared claims yet.
Their compatibility fixtures are being upgraded with signed effect metadata.
Qualification also pins both published SDK and contracts JARs and their resolved
application classpaths, so a same-version transitive artifact mismatch fails.
No deduplication, generalized route grant, exact-consent or complete roadmap
phase is claimed by this protocol delivery.

Executed validation:
- Full javaCheck passed with real PostgreSQL: enterprise 189 passed with no
  skips, main 260 passed with five configured skips, service SDK 25 passed
  with no skips. Bridge tests cover first-use refusal before SENDING, changed
  facts, revocation, same/different-token replay, wrong audience, late expiry,
  terminal-invocation abandonment, signing-key replacement, idempotent outcomes
  and conflicting evidence. The claim path also executes under tenant RLS.
- Rust workspace formatting/tests/Clippy passed. Java and Rust contract
  round-trips were rerun after correcting one structural fixture's missing
  sha256 prefix. Python contract rerun passed 136 with two configured
  PostgreSQL-only skips (138 total). TypeScript's two tests passed; PHP 287 v1
  and 250 v2 model/security round-trips passed. Bindings (1125 files),
  structural fixtures and UI operation generation verified.
- Final real rootless gate passed six cases on x86_64, backing device 8:80,
  using the previously pinned immutable Python image. Its known parent is
  afce2106b326f2296433507a56dd15c74dac47fe; full snapshot SHA-256:
  fc38fcdec941fad26cd96b2b96d93a2baaa1c1e781ec0c88561cb31852e37f1b.
- Actual knowledge qualification passed all 28 tests without skips, including
  real PostgreSQL/TLS/Qdrant/Ollama with scripted Control and the retrieval
  evaluation. Component source SHA-256:
  a00ccb2722d9cf179235bd65bdc43338cb81d79b8970d87399cb65d0e437e260.
  SDK SHA-256:
  8a574e87a9b75caf81d761d89a7fe15f0de6614b6a3dd900d9cd55c26b23939b.
  Contracts SHA-256:
  1daad128498a9dccd8a9bcf1969962213ff7d341a1716f8e52d732ea6fe28cc7.
  Coordinated knowledge compatibility is pushed as olo-mono commit f885c89.
  All three module runtime classpaths matched those JARs. Positive verify-only
  checks passed; temporary report copies with changed contracts/classpaths
  and skipped tests were refused. Full-stack and production SLO acceptance
  remain outstanding.

Known ToolGate parent: afce2106b326f2296433507a56dd15c74dac47fe.
Changed source SHA-256:
9117788220c17249a7d368f2c9e51645d0c25da1d74194c29e1d12f48b010065.
Input is the sorted changed/new paths beneath apps/, backend-libs/,
packages/contracts/, tests/fixtures/ and tools/security/, one NUL and exact
bytes (30 files). Documentation is excluded. The rootless snapshot predates
these final evidence/documentation additions.

Next: verified target-claim orchestration and actual memory/RAG
transaction/recovery integration.


## Verified target-claim orchestration delivery

[ADR 044](../adr/044-target-claim-orchestration.md) separates the SDK's
VerifiedWrite facts from commit authorization. The actual OLO adapter commits its
unique local claim before online consumption, then commits APPLYING before any
business action. RAG also checks the original deadline before embedding and
external vector mutation. Completion and a durable outcome record share the
business SQL transaction; the reporter retries only evidence delivery.

Expired CLAIMED recovery commits ABANDONED and not-started evidence atomically,
fencing a paused owner. APPLYING is never automatically reported not-started.
Legacy expiry 0 remains unknown. Bounded, coalesced recovery and keyset outcome
pagination prevent permanently deferred records from starving later evidence.
Owner/outcome representations redact tokens. Target migrations force tenant
RLS, restrict runtime writes to transition/delivery columns, prohibit deletion
and guard immutable bindings/state transitions. Missing/unsafe schema fails
startup; writes have no one-shot fallback.

This remains an OLO/component slice. An outcome unknown to Control stays queued
for reconciliation without authority to resend. There is no automatic replacement
for abandoned/uncertain effects. Generalized routes, exact consent, whole-operation
finalization and production SLO/release acceptance remain outstanding.

Executed validation:
- Final full javaCheck passed with a fresh real PostgreSQL fixture: Control
  enterprise 189 passed without skips, main 260 passed with five configured
  skips, service SDK 30 passed without skips. New SDK cases cover verified facts
  without consumption, original deadline across slow consumers, durable outcome
  delivery/acknowledgement, keyset pagination, atomic abandonment evidence and
  compatible v1 migration without invented expiry.
- Actual knowledge qualification passed **32 tests, no skips**: service-core 5,
  memory 10, RAG 17. Real PostgreSQL/TLS/Qdrant/Ollama with scripted Control.
  Each TLS fixture uses two application JVMs, paused-owner fencing, refused/
  other-claim/wrong-generation replies, one racing business mutation, writer
  termination and lost outcome acknowledgement recovered by the survivor.
  SQL cases check forced RLS, immutable binding/deletion privileges and state
  guards; expiry after embedding refuses the external vector write.
- Memory/RAG distributions built and installed SDK/contracts hashes matched
  their qualified runtime classpaths. Full-stack provisioning syntax checked.
  Positive verify-only and temporary-copy changed-contract/classpath/skipped
  evidence negative controls passed. Full Control/Gateway and production SLO
  qualification remain outstanding.
- No Rust, containment or wire-schema source changed in this increment; their
  executed evidence remains the preceding known delivery.

Known ToolGate parent: 3665e52d7b8c622d4d776361eff0a564ad961c32.
Changed SDK source SHA-256:
6f0d13e8c6fe92617354d66c46d6bc4c40b7931990c69fded03574a79b9da64e.
Input is the five sorted changed Java main/test paths beneath
backend-libs/service-authorization/, one NUL and exact bytes; documentation
is excluded. Complete verifier-source SHA-256:
3287fa65030eea04ccc0471f89939cc63312e700e67c487323b23858193453e1.
Coordinated knowledge integration is pushed as olo-mono commit 690c08b.
Qualified knowledge component SHA-256:
8d661624055f36d4ebb0b6da67c9a20f4679c6b0b4d96b7673b625fc0cd37c6a.
SDK JAR SHA-256:
16e961a02fc5b032578b8aecb1bf41daad0de157a2905c6552c7ce88307d35fe.
Contracts JAR SHA-256:
1daad128498a9dccd8a9bcf1969962213ff7d341a1716f8e52d732ea6fe28cc7.

Next: actual Control/Gateway qualification for this integrated target path, then
generalized effect grants/keys and exact consent slots.

## Actual Control/Gateway qualification and weighted baseline

The owned development fixture passed Gateway → Control → Tool Host Java packages
→ mTLS memory/RAG → databases, including stable invocation replay/concurrent
replay, identity/tenant/resource refusals, governed RAG retrieval, ACL narrowing,
deletion, quotas and live grant revocation. The gate independently joins committed
target DONE/outbox records with Control effect ID, nonce, authorization generation,
consumer and COMPLETED state; both services must settle. Missing or conflicting
records, premature acknowledgement, changed source/JARs or stale image evidence
are rejected. See [ADR 045](../adr/045-knowledge-stack-qualification.md).

Executed evidence:
- Runtime source revision: 1ad12d83c1d285614669c17e3ee2dd67933ff293.
- Coordinated knowledge qualification commit: 8e3f7a7 (olo-mono feature branch).
- Runtime image: sha256:cfd5fa9a28156bfd57d9d9ebfae60d9fc3fb5d0b3c1299d069f5c45c62aa8f11.
- Qualification runner SHA-256: 478e7298c90449fdcd5b404d22c8d56dd857cd054bea7a567e5e8efcec63a97a.
- Knowledge code SHA-256: 9552f0a7fecaf65c420540126beeb7e127efaea53c53c45662c676ba8ffc094c.
- Component tests: 32 passed, no skips; SDK check/publication passed.
- New qualification proof tests: 4 ToolGate and 9 knowledge harness tests passed.
- Full-stack conformance report passed with both target/Control outcome joins.

The fixture uses development loopback transport for Gateway/Control and verified
TLS/mTLS for target boundaries. It does not establish production CNI isolation,
revocation SLOs, complete Control/Gateway crash/partition recovery or generalized
route/consent guarantees. Those remain explicit stages; no full phase is accepted.
The initial 22.5% baseline credited bounded prior deliveries, including the validated
S17 slice; subsequent S03 completion raises it to 23.7%. Neither percentage
is a fraction of security claims accepted.

## S03 — Adopted threat matrix and security documentation

Known tested parent: ea035dc0 (ToolGate feature branch). The new
[security model](zero-trust-boundaries.md) adopts all nine attacker classes,
trusted components, residual risks and C1–C8 falsification criteria. Platform,
Tool Host, OLO, endpoint, key-custody, security invariants, architecture boundaries
and top-level security documentation now reference that model and distinguish
qualified scope from required design. The root-compromised-host lease claim and
the OLO write-consumption transaction description were corrected. No enforcement,
schema, key, database or protocol behavior changed; no new ADR is required.

Executed validation: documentation structure check (make docs), local link-target
checks, preserved-plan byte comparison and git diff whitespace check. This is
documentation qualification, not execution of the full C1–C8 test matrix.
Required commit hooks additionally run before the stage is delivered. All 13 existing conformance-definition tests passed, including the check that
every Tool Host threat maps to an authored case. These validate definitions, not
runtime security behavior. Final local-target check: 152 targets resolved. Documentation source SHA-256:
ca375420eb5c40bb15474ca06a472f567a806c70992877475ebf3a3f0c1e02e3
(11 changed Markdown files, excluding this live status document; sorted
repository-relative path + NUL + file bytes). The ignored report is
build/docs/documentation-s03/report.json. Initial report writing under the
root-owned build/security directory was refused; rerunning under the
user-owned build/docs directory passed. No runtime suites were rerun for this
documentation-only stage; runtime evidence retains its previously recorded scope.

S03 earns its fixed 0.5 planning-week weight. Original weights/scope are unchanged:
completion moves from 8.875/39.5 (22.5%) to 9.375/39.5 (23.7%).
All seven full phases and the first vertical slice still have outstanding exit
criteria. Next: S04, executable release conformance scorecard and CI coverage;
unexecuted requirements must remain explicit failures or missing evidence.
