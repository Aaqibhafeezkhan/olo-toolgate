<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Zero Trust Execution implementation status

The [revision-10 plan](../plans/zero-trust-tool-execution.md) is the preserved
proposal linked by issue #230. A merged design or an authored conformance case
does not establish a passing security claim.

## Overall task progress

Updated: **2026-11-02**. Branch: `feature/zero-trust-execution-plan`.
Previous containment delivery: [`0813b71b`](https://github.com/olo-labs/olo-toolgate/commit/0813b71bbe2a0f9276a1b2e2260b25454a31d3d4).

**Overall: partially implemented.** Scoped deliveries now include:
renewable leases/durable execution recovery, qualified Python OCI containment,
explicit business-key binding, independent operation/permit-attempt journaling, an effect-generation foundation, exact broker transport preparation (explicitly restricted to HTTP/1.1), OLO service HTTP fingerprint grants, independent Control validation of their pinned manifests, actual knowledge-target transport integration, owner-bound OLO single-dispatch gates, a shared SQL target-claim foundation, OLO claim-token consumption/status/outcome APIs, verified target-claim orchestration with memory/RAG transaction integration, source-bound actual Control/Gateway/target outcome qualification, adoption of the complete A1–A9/C1–C8 security model, a fail-closed diagnostic scorecard/CI harness, and reviewed business-key policy with immutable window history and retention maintenance, and proof-bound whole-invocation recovery with immutable predecessor archives and stale-receipt fencing; plus S10 all-attempt finalization, immutable closure/settlement and current-authority caller history under the explicit stage scope amendment below; S12 per-invocation broker identity/network separation with scoped private authority/transport IPC and paired teardown/recovery; and S13 strict worker framing and declared-tier containment/interface/egress qualification with verified-leaf TLS pins. Executed validation for the current
delivery is recorded below. **0 of 7 full roadmap phases have met every exit
criterion.** Phases 0–3 have
partial delivery; phases 4–6 have not started as generalized implementations.
**Weighted delivery progress: 42.7% complete; 57.3% pending.** This planning metric credits only completed delivery stages, not partially completed stages. It is separate from full-phase security acceptance (still 0 of 7). See the baseline below.

The roadmap defines **7 major phases**, not a fixed number of incremental
deliveries. Its original per-phase estimates sum to approximately **35–44
weeks for a sequential phase schedule**; phases 2 and 3 may overlap. These are
the proposal's full-plan estimates, not a measured remaining effort or calendar
completion forecast. The midpoint of each original phase estimate supplies the fixed planning weight below; this is not a measured remaining-effort forecast.

| Plan phase | Status | Delivered | Remaining before full phase completion |
|---|---|---|---|
| 0 — Boundary and harness | Partial | Weighted delivery baseline and one-commit stage workflow; source-bound actual stack gate; adopted A1–A9/C1–C8 threat model across security docs; diagnostic scorecard/CI harness inventories all 220 current security/integration cases (209 original plus 11 S10 component vectors) with explicit missing coverage; preserved revision-10 plan, claim inventory and executable OCI conformance gate with revision/source/image evidence. | Execute the full C1–C8 case/target matrix and integrate a strict release scorecard; obtain hosted CI/release evidence. Four delivery-stage checkmarks do not satisfy these full-phase exit criteria. |
| 1 — Operations, leases and evidence | Partial | Durable device/fence-bound leases; heartbeat/watchdog; admission spool; result outbox; immutable late executor evidence; SQLite/PostgreSQL migrations and tests; conservative business-key binding; V3 operation/attempt records, immutable snapshots, historical backfill, positive-proof reservation replacement and conservative operation summaries; reviewed required/argument keys, verified namespaces, bounded optional dedupe windows, immutable generation history and retention tombstones, tested on SQLite/PostgreSQL and Gateway/human paths; S09 whole-operation recovery with fresh current authority, retained attempts/leases/dispatches and late-receipt fencing. S10 complete all-attempt/effect derivation for the declared qualified OLO path, immutable manifests/settlement/finality, independent scope review, PARTIAL business keys, bounded current-authority history and Gateway/MCP/native/console presentation; qualified component vectors and affected regression/OCI/target gates pass. | Complete original distributed transitions/audit-spool/duplicate-execution suites remain required, including task/input, kill, host-spool lifecycle and crash/redispatch assertions. S10 is Done under the explicit bounded scope amendment; this does not accept the original full suites or phase. Live consent/equivalence/generalized target-guarantee sources retain their S19/S20 requirements. S09 delivers public proof-bound recovery, immutable predecessor archives, original deadlines, qualified released-generation replacement and SQLite/PostgreSQL/OCI evidence; it does not accept the entire phase. |
| 2 — Containment | Partial; declared-tier runtime/interface slices delivered | Rootless Python workers and per-invocation brokers with disjoint identities/namespaces, pinned images, scoped private authority/transport and paired cleanup. S13 adds strict canonical worker frames, bounded state/output, checked DNS/literal addresses, verified-leaf TLS pins, current-source matrix, sequential state/descriptor isolation and unsupported-profile refusal before consumption. Final matrix passes 100 native/component and 12 actual OCI tests. | S14 deployment lifecycle/crash/cleanup and measured bounds. Full historical optional-transport/generalized-kill, production/Kubernetes and release acceptance remain outside this declared-tier delivery. Other runtime/OS/VM tiers remain unqualified. |
| 3 — Effect authorization and recovery | Partial; ledger foundation | Durable immutable effect identities; authorization generations/owners; release/send/consume/ambiguity states; target-vs-broker evidence; timeout and terminal-invocation abandonment. Integrated with existing OLO write permits. Broker-owned prepared HTTP requests now have versioned fingerprints and explicit no-retry HTTP/1.1 transport. OLO service permits sign those digests and require target recomputation before consumption. Leased v2 issuance independently checks signed admission and pinned route/profile manifests. The actual memory/RAG listener integration supplies accepted HTTP facts using trusted profile configuration and refuses unbound downgrade. Bound OLO writes require a recorded process/handler owner and a single authenticated AUTHORIZED-to-SENDING transition before dispatch. A target-owned JDBC claim foundation now supports unique ownership, apply/recovery CAS and atomic business completion; the actual memory/RAG adapters now integrate it. Bound write claims now sign effect identity/generation; dedicated service APIs return consumption status and retain authenticated late target outcomes. The target SDK separates verified facts from online consumption, persists APPLYING before external actions, and atomically journals completion/outcome delivery with business SQL. The owned actual Control/Gateway fixture now verifies committed memory/RAG outcome settlement against Control records. | Generalize the independently validated OLO path to broker enforcement for all route classes, effect keys/manifests, generalized owner protocol, consent slots, generalized target fencing/deduplication, reconciliation and operation finalization. |
| 4 — Evidence presentation and tamper evidence | Not started | Raw executor evidence is retained by phase 1. | Outcome grades in presentation/exports, tenant audit chains, signed external anchors and offline verification. |
| 5 — Identity hardening | Not started | Existing device certificates remain the baseline. | Sender-constrained agent-run credentials, narrowing delegation and continuously verified executor posture. |
| 6 — Policy differentiation | Not started | Existing approvals remain the baseline. | Broker OAuth/token exchange, trusted provenance, exact effect previews and stronger step-up approvals. |

### First vertical-slice acceptance

The complete first milestone in issue #230 is **not yet accepted**.
S03 adopts the [threat model and claim definitions](zero-trust-boundaries.md);
documentation alignment supplies no additional runtime acceptance. S04 now publishes diagnostic gaps and scoped observations, not vertical-slice acceptance. All acceptance
items below retain their existing implementation status. S08 adds admission/window integrity evidence; S09 adds proof-bound replay, rollback/race and late-receipt fencing checks with its full-stage checks now passing. Neither accepts the milestone.

| Acceptance item | Status | Evidence / outstanding work |
|---|---|---|
| Exact consent rejects recipient changes and additional effects | Not implemented | OLO fingerprints are independently route/profile-checked by Control and signed/target-checked, including actual memory/RAG component TLS tests; independent exact consent slots and additional-effect rejection remain unimplemented. |
| Revocation refuses subsequent sensitive effects and stops healthy workers | Partial | Lease revocation tears down both a real OCI SDK worker and its isolated broker; broker loss and worker private-channel forgery also terminate the pair, with zero Control contact on forgery; S13 raw socket witnesses additionally prove pending-call cancellation and diagnostic failure close the target socket. Generalized sensitive-effect authorization/signed kill and measured deployment bounds remain outstanding. |
| Crashes around dispatch/target commit preserve evidence and prevent unsafe retries | Partial | Admission/result recovery, database reopen, immutable attempt snapshots and refusal of consumed-attempt replay are tested; S09 focused SQLite/PostgreSQL checks cover atomic rollback, competing public replay, fresh permits, original deadlines and stale receipt fencing; S08 adds two-Control-instance window races, immutable history/retention and refusal to rotate ambiguous work on SQLite/PostgreSQL; TLS tests verify HTTP/1.1-only dispatch and target refusal of changed transport facts before storage; owner races permit one SENDING winner and refused gates contact no target; actual target tests now exercise paused-owner abandonment, two JVMs, writer loss after business commit and lost outcome acknowledgement; complete Control/Gateway crash scenarios remain outstanding. |
| Partition, duplicates and concurrent target replicas exercise fencing/recovery | Partial | Host Control-partition and OCI recovery tests pass; actual knowledge component storage/TLS/vector qualification is now available; shared SQLite/PostgreSQL claims and actual memory/RAG JVM replicas now exercise one mutation, paused-owner fencing, writer loss and outcome redelivery; actual Control/Gateway normal-path settlement now passes; complete partition/crash qualification remains outstanding. |
| Operation finalization includes all attempts/effects and late evidence | Partial; bounded S10 delivered | Control derives the union of every bounded attempt/effect generation, closes immutable manifests and settles only with positive automatic/human scope evidence. Qualified late reports contribute without rewriting current dispatch; conflicts fence authority and final outcomes latch reconciliation. Current-authority caller/history, business-key projections and console presentation pass component regression checks; 143 renewed Control cases (including 17 policy cases and required PostgreSQL scenarios), six actual OCI tests and 32 current target cases pass. S10 is Done only under the recorded scope amendment. Full original distributed suites and later independent consent/equivalence/deduplication sources remain required for full acceptance. |
| Revision-bound evidence distinguishes fenced/unfenced guarantees | Partial | S13 final OCI/matrix reports match known parent f7b65600, source 1baf7f3de125c66d7b8d2921e795e6a2560a8d9c4817fc6e6a6642f202f1f009, both image pins, static broker binary and x86_64 architecture. They pass 12 actual OCI plus 100 native/component witnesses in all eight declared-scope matrix groups. Stale source evidence is refused; securityAccepted=false and originalCatalogsAccepted=false remain explicit. Deployment SLOs, full historical catalogs and complete C1–C8 acceptance remain outstanding. |

**Next implementation stage:** S14, containment deployment qualification.
S03, the clarified S04 diagnostic harness, S09 proof-bound rebinding and the explicitly amended S10 finalization stage are complete. Continue in stage order;
dependencies may be implemented inside a stage but cannot earn duplicate credit.
S17 development-stack qualification is complete; generalized grants, exact consent,
full distributed operation acceptance and production deployment evidence remain outstanding.

### Weighted delivery baseline (2026-11-01)

This is a prospective planning model, not a measured fraction of engineering hours
or a security assurance percentage. Phase weights use the original estimate
midpoints: 2 + 7 + 7 + 7 + 3.5 + 6 + 7 = **39.5 planning weeks**.
Each phase's weight is split equally among its explicitly defined delivery stages.
Equal weighting within a phase is a planning assumption, not observed effort.
A stage earns its entire weight only when its stated scope and executed validation
are complete; Partial and Pending earn zero. Existing work is credited only for
the bounded Done stages below, not for the complete parent phase.

**Completed weight = 2 + 7 + 5.25 + 2.625 = 16.875.**
**Completion = 100 × 16.875 / 39.5 = 42.7% (rounded).**
Pending weight is 22.625, or 57.3% (rounded). Sixteen of 33 delivery stages are Done;
S10 credit uses the explicit scope amendment below, not full distributed-suite acceptance;
stage count is not the percentage formula. No phase receives full acceptance
until all its original exit criteria have executed evidence.

| Roadmap phase | Planning weight (weeks) | Stages done / total | Earned weight | Weighted contribution |
|---|---:|---:|---:|---:|
| 0 | 2 | 4 / 4 | 2 | 5.06% |
| 1 | 7 | 6 / 6 | 7 | 17.72% |
| 2 | 7 | 3 / 4 | 5.25 | 13.29% |
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
| S04 | 0 | Complete diagnostic scorecard and CI harness | Done | Inventory all required security/integration cases; execute supported observations; report missing/failed/stale evidence without acceptance; publish revision-bound CI diagnostics. Full case implementations and release acceptance remain in their owning stages/S33; local validation passed, hosted CI not yet observed. |
| S05 | 1 | Renewable execution leases | Done | Lease, heartbeat, watchdog, revocation and expiry tests. |
| S06 | 1 | Durable admission and evidence delivery | Done | Admission spool, result outbox and immutable late evidence; recovery tests. |
| S07 | 1 | Independent attempt journal | Done | V3 attempts, immutable snapshots and historical backfill tests. |
| S08 | 1 | Generalized business-key policy | Done | Reviewed required/argument keys and verified namespaces; optional windows require resolved Control proof and immutable history; bounded retention tombstones; SQLite/PostgreSQL, contracts, Gateway/human and target-component gates (evidence below). |
| S09 | 1 | Whole-invocation rebinding | Done | Complete-journal proof, original deadline, qualified broker-only released-generation replacement, immutable recovery archives, rollback/replay races and stale receipt fencing; SQLite/PostgreSQL and actual OCI gates pass. |
| S10 | 1 | Complete operation finalization and callers | Done | Aggregate every attempt/effect and late evidence; authoritative closure/settlement, current-authority caller history/presentation, and source-bound finalization component vectors in transitions/audit-spool/duplicate-execution. Full original distributed-suite acceptance remains a phase-1/S33 exit requirement (scope amendment below). |
| S11 | 2 | Qualified Python worker containment | Done | Rootless OCI namespaces, filesystem, resources, no fallback and teardown/recovery tests. |
| S12 | 2 | Trusted broker OS/network separation | Done | Scoped private authority/transport IPC, pinned broker image, disjoint subordinate identities and user/network namespaces, no in-process fallback, paired teardown/recovery; eight final source/image-bound actual OCI cases, focused native/transport/authority tests, workspace checks and CI contracts pass (details below). |
| S13 | 2 | Complete containment/interface conformance | Done | Complete declared Linux x86_64 Python OCI matrix: strict schemas/frames, state/descriptor/kernel/network isolation, no fallback, pending-call ordering/cancellation, checked addresses/redirects/TLS pins and shared MCP TLS compatibility. Final source-bound report passes 100 native/component and 12 actual OCI tests; disabled transports/other tiers and full original-catalog limitations are explicit. |
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
| S33 | 6 | Stronger step-up approvals and final acceptance | Pending | Step-up binds identity/effect/deadline; flagship demo, external red-team exercise and complete release/phase acceptance, including all missing case adapters and strict C1–C8 release gating from S04. |

### Explicit S10 baseline scope amendment — 2026-11-02

Old S10 completion text: "Aggregate every attempt/effect and late evidence; caller
history/presentation and transitions/audit-spool/duplicate-execution acceptance."
The implementation log initially interpreted the last clause as requiring every
legacy case in those catalogs before delivering S10. Those catalogs include
platform task/input, host-spool lifecycle, kill and generalized resend assertions.
S10 also needs to fail closed while the independently approved consent/equivalence/
target-deduplication sources owned by S19/S20 are unavailable. Treating all those
later requirements as S10 prerequisites conflicts with the stage delivery order.

New S10 completion text is the explicit bounded feature scope in the table:
complete derivation over the authoritative attempt/effect union, immutable
closure/settlement/finality, qualified late evidence, current-authority callers and
source-bound finalization component vectors in those three catalogs. Verify pure
rules (including prospective fixed-plan/equivalence/deduplication cases), both SQL
dialects/runtime-role guards, all affected caller/console/contracts regressions,
actual qualified OCI containment and current target-component artifacts. Independent
future authority sources remain unavailable, never simulated as live guarantees.

This is a **scope amendment**, not a claim that the old entire-suite interpretation
has passed. No original requirement or phase exit criterion is removed. Complete
original distributed suites remain mandatory for phase 1 and S33, whose existing
scope already includes all missing case adapters and complete phase acceptance.
S19/S20 retain ownership of their live independent authority sources. Any stage
credit below uses this amended bounded S10 criterion; no extra credit is given for
the deferred acceptance work.

Stage counts, phase weights, equal within-phase allocation and the **39.5-week
denominator stay unchanged**. While S10 is Partial, completed weight stays
12.208333… and the score stays **30.9% / 69.1%**. If and only if the amended S10
scope and checks finish, its 7/6 weight brings completed weight to 13.375
(**33.9% complete / 66.1% pending**, rounded; 14/33 Done). Even then phase 1 and
all other roadmap phases remain unaccepted until their full original exits pass.

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
| C3b, current per-effect authorization | Partial; OLO write path only | Existing OLO current-authority checks now reserve/consume durable effect generations atomically. OLO permits also sign prepared HTTP fingerprints and the target verifier checks accepted-request facts. Control now independently verifies pinned OLO profile/route manifests and original signed admission scope; v2 leases cannot downgrade to unbound issuance. Actual memory/RAG listeners supply HTTP facts from accepted requests with trusted deployment bindings. Bound write dispatch now rechecks current authority and recorded ownership at a single SENDING transition; bound target consumption refuses unsent generations. A shared SQL claim-store foundation is tested independently. Control now exposes claim-token consumption/status and late authenticated outcomes; actual memory/RAG integration now uses verified facts, durable claims and a target outcome outbox. Component crash cases use scripted Control; the additional owned development fixture now joins actual Control outcome records to committed target claims. S12's working-tree supervisor port now requires authorized=true at the send gate; HTTP 403 and HTTP 200/false both contact no actual mTLS target, while a positive gate sends once. S12's private supervisor delegate additionally fixes invocation/fence/profile/owner scope and one-use transport, with native helper and eight actual OCI cases, including zero-Control-contact worker impersonation. S13 strict framing precedes authority handling; diagnostic failure drops an outstanding authority/transport future and its target socket. Verified TLS/leaf pins do not replace Control authorization. Production deployment evidence remains outstanding. Generalized enforcement, exact consent and complete stack/deployment conformance remain required. |
| C3c, bounded unmediated execution | Partial | Native direct-worker supervision and OCI teardown on cancellation/deadline are tested. Enforced service cgroups contain worker processes; measured deployment revocation SLOs remain required. |
| C4, bound admission intent | Implemented existing boundary | Tool Host admission binds signature, audience, arguments and content digests; dispatch integration tests. |
| C5, contained execution | Available for qualified Linux x86_64 Python OCI pools | S12/S13 source/image-bound actual isolation and paired lifecycle evidence is extended by a complete declared-tier matrix: 12 OCI and 100 native/component tests, including strict framing/floods, fresh state/FDs, resource/syscall/network probes, zero-authority forgery, TLS chain/leaf pins, address/redirect policy and pending-call cancellation. Shared native MCP TLS tests earn no native containment credit. Supervisor/broker/engine/kernel stay trusted. Full original optional-transport/kill catalogs, other tiers, deployment SLOs and complete security acceptance remain outstanding. |
| C6, honest tamper-evident record | Partial | Durable admission, immutable executor evidence, independent immutable attempt snapshots, immutable recovery archives and host result retry; standalone SDK SQLite restart/late-completion recovery uses immediate writer transactions. S10 derived-outcome/scope-review records and audit share writer transactions; rollback and replay checks pass. Eleven source-bound component vectors passed without promoting original distributed suites. Hash chains and external anchors remain required. |
| C7, revocation | Partial | Renewable leases and current authority checks; full target and compromised-host distinctions remain required. |
| C8, operation/effect integrity | Partial | Single-use admission and durable explicit operation keys exist; keyed unknown outcomes cannot reopen via legacy resend. Independent permit attempts and positive-proof reservation replacement are journaled; unqualified or potentially sent consumed work remains fenced. S09 implements immutable proof-bound whole-operation recovery, with default-off admission-time device/pool/image qualification and all predecessor generations released; full-stage verification passes. Effect generations now separate authorization from consumption/outcome on the OLO write path. The HTTP/1.1 broker now binds OLO service fingerprints through signed claims, immutable effect identity and target checks. Pinned OLO request/route validation is independent at Control. A process/handler-owned single-send gate prevents replaying a bound generation's dispatch authority. The SQL target claim foundation tests unique ownership and atomic business completion. Claim-token consumption/status and late target outcomes are exposed at Control, and actual memory/RAG transactions now adopt them with restricted SQL state/binding guards and durable outcome delivery. Reviewed required/argument keys, verified namespaces, proof-bound windows and retention tombstones are implemented and tested across Control, Gateway and human admission. S10 all-attempt derivation, immutable finality, automatic/human scope settlement, late evidence, current-context caller history and console/business-key projections pass the amended stage gates. Full original distributed conformance remains required, alongside other route classes, consent slots and independently approved generalized target deduplication/equivalence sources. |

The adopted [A1–A9 matrix and C1–C8 definitions](zero-trust-boundaries.md)
now apply across the security documentation. S03 corrects claim scope, especially
C2's sender constraint, C3b's effect ordering, C3c/C7's healthy-host limitation,
C5's qualified runtime scope, C6's missing anchors and C8's ambiguous-retry rule;
it changes no runtime guarantee or claim implementation status. The S04 diagnostic scorecard additionally records all ten claim checks as INCOMPLETE and does not promote scoped observations to whole-claim evidence.

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


## S04 — Diagnostic scorecard and CI harness

The stage definition is clarified to avoid a dependency cycle: the earlier
wording required every security case to execute before the later stages
implementing those controls. The original phase-0 proposal explicitly allows
failing conformance. S04 delivers the executable diagnostic harness and complete
inventory; full case adapters, target matrices and release acceptance stay with
their owning implementation stages and S33. Phase 0 is not accepted by a green
diagnostic job. No weights or denominator change; the validated harness earns S04's fixed
0.5 weight on delivery, moving progress from 23.7% to 25.0%. This is a recorded scope
clarification, not an increase in delivered runtime guarantees.

The current implementation inventories 209 security/integration definitions,
reports all complete case adapters MISSING, and retains one separately scoped
actual Rust mTLS send-order observation. C1–C8 (ten checks including C3a/b/c)
remain INCOMPLETE; declared passing metadata earns no credit. The strict CLI
returns nonzero for incomplete acceptance. CI uploads JSON, Markdown and logs,
then independently checks revision/source, inventory, observation execution,
log hash and summary agreement; producer failures cannot hide behind the
expected nonzero acceptance result. Automatic/manual CI and local coverage
classification are kept aligned. See [the guide](../testing/zero-trust-scorecard.md).

Focused validation so far: nine negative/positive scorecard tests, 57 workflow/
local-runner tests, 32 admission tests, and the actual single-send mTLS test.
The first broad CI run found workflow integration defects (fixed) and unrelated
Windows PowerShell interop failures in the inherited WSL PATH. A rerun with the
documented Linux tool PATH exposed one remaining manual-job expectation (fixed).
Final validation passed: the CI suite ran 380 tests (363 passed, 17 explicitly
skipped for unavailable platform/tool prerequisites), all nine scorecard tests
passed, all 13 conformance-definition tests passed, make docs and whitespace
checks passed. The fresh actual mTLS observation passed and the strict command
returned 1 as required for incomplete acceptance. The independent diagnostic
publication verifier passed. Hosted GitHub execution was not run locally.

Known tested parent: 57b18790. Full source snapshot from the fresh observation:
c9ef71d0b0bb54ef441fad3d57783f5d2ca641db106f474e742a8dd72ea0cb5b.
That snapshot precedes this final validation-text/status append. Changed
implementation/workflow/test source digest (sorted path + NUL + raw bytes):
8925e26060ec1ad292a684466b1b9401104d084a6712c7b080a30d359d1a78b7 (8 files; Markdown excluded).
Generated JSON/Markdown and observation logs are retained under ignored
build/zero-trust-scorecard. They are diagnostic evidence, not a signed artifact
or a completed C1–C8 release scorecard. Full case adapters, exact consent,
generalized enforcement and production qualification remain outstanding.

Scope clarification history: S04's original wording required every case to
execute before later stages implemented those cases. The delivered definition
is the complete phase-0 diagnostic harness (failures/missing coverage explicit);
full case execution remains in the owning stages and S33. No case implementation
was deleted, no baseline weight changed, and no runtime/security completion is
credited for that deferred work. Phase 0 remains Partial despite 4/4 bounded
delivery stages, because full phase acceptance still requires complete executed
release evidence. The next stage at that checkpoint was S08, generalized business-key policy.


## S08 — Generalized business-key policy

[ADR 046](../adr/046-business-key-policy-retention.md) and the
[business-key guide](../operations/business-operation-keys.md) document this
delivered stage. Reviewed optional metadata supports required keys, verified
AGENT/DELEGATED_USER/TENANT namespaces, exact top-level argument keys, retention
and optional dedupe windows. Header and argument keys must agree. Current
authorization still applies on replay. Pinned server descriptors provide an
independent policy floor; signed client profiles carry policy; Control replaces
device policy hints in discovery. Gateway checks derived/canonical IDs across
native/MCP flows. Human local dispatch uses the canonical ID, and the console
forwards a declared argument key as its header key.

V5 adds immutable tenant-scoped window history and stricter key guards. Rotation
archives the exact predecessor atomically and requires durable non-legacy
resolved-operation proof. Time, missing evidence, legacy rows, running work and
consumed unknown outcomes cannot justify rotation. Retention is immutable within
a generation. Bounded maintenance tombstones resolved expired reservations,
never deletes identities or revives explicit keys. The reviewed storage manifest
and real PostgreSQL privilege tests cover the new SELECT/INSERT-only forced-RLS
history table. Unshipped kill-switch specifications move byte-for-byte from V5
to V6; shipped migrations and the revision-10 plan remain unchanged.

### Executed validation

Known tested parent: 4d60887c1a60553caba4c261727b4ee727e0b5d6.
Pre-final-progress tracked/untracked nonignored source snapshot:
f0a28d3a34bd85099a2e1a4071e7f3709e84dab081f69925277f104fe63a9a2f.
Deterministic code/source digest:
4b8639e6978713446ebf6bf3f74cbbc0602611e4fb8062c2c48b054f96ba1366
(2380 sorted Git files with java/rs/sql/json/py/ts/tsx/php/kts/gradle suffixes;
SHA-256 over path + NUL + binary file SHA-256). No future commit SHA is asserted.

- Full javaCheck passes with isolated real PostgreSQL. Control enterprise:
  220 tests, no failures/skips, including 70 operation tests and eight pure
  policy cases. Control main: 267 tests, zero failures, five explicit skips:
  optional insights benchmark, optional real Redis, three JSON SDK worker cases.
  Package/release policy tests pass. Initial full runs exposed migration-count
  expectations and the omitted ownership-manifest entry; both were fixed and
  the full Java gate rerun successfully.
- Contract regression: 155 tests, 153 passed and two environment-dependent skips.
  The PostgreSQL migration-spec gate was separately executed: all three
  SQLite/PostgreSQL/spec-version tests pass without skips. The other suite skip
  is not promoted to acceptance. Four focused policy-schema cases include exact
  frozen-reader incompatibility checks; archived schemas remain unchanged.
  Generation verifies 1129 files; structural corpus check passes.
- Full Rust workspace tests, formatting and Clippy with warnings denied pass.
  Focused Gateway core: 36 cases pass, including authoritative discovery,
  required keys, namespace/argument agreement and canonical-ID refusals.
  Six focused console cases pass, including argument-key forwarding and malformed
  key refusal before submission.
- TypeScript v1/v2 round trips pass. Container PHP 8.2 executes 289 v1 and 250 v2
  round trips plus security negatives; all generated PHP syntax checks pass.
  The broader Python group passes source quality, 155 contract cases and 76
  Python SDK cases, then stops because native PHP is absent. PHP checks were
  executed separately with supported container tooling.
- Six actual rootless OCI cases pass on x86_64, backing device 8:80, image
  docker.io/library/python@sha256:aa943ce411423c7582288f18707ff66eddcbaeb510502d747a72aeb34f30d159,
  tested source bb84726a943808ca7a1a59a0b385b4cda16a23495750db4a633842b40566a9ff.
  This snapshot precedes ownership-manifest/documentation corrections;
  containment/runtime sources remain unchanged afterward.
- Actual knowledge qualification passes all 32 component tests without skips:
  core 5, memory 10, RAG 17, with PostgreSQL, mTLS, vectors, evaluation and
  replica/crash cases. An initial concurrent run failed memory JVM readiness
  before policy assertions; the isolated complete rerun passes. Target revision:
  8e3f7a7141119b800cf10248ab2e2298bc8587dc; source:
  9552f0a7fecaf65c420540126beeb7e127efaea53c53c45662c676ba8ffc094c.
  All three module classpaths pin contracts JAR
  b67fbb51e89047aa7fcc2331f2fd7e77521e8ea8bcfeac93a85aa133fae45b50
  and service SDK
  16e961a02fc5b032578b8aecb1bf41daad0de157a2905c6552c7ce88307d35fe.
- Documentation structure, changed-document local links, preserved-plan bytes
  and whitespace checks pass. Required commit hooks verify committed UI,
  browser behavior, metadata/policy and staged secrets before creating the commit.

### Limits and next work

This completes S08's bounded delivery, not phase 1 or C8 acceptance.
DELEGATED_USER still binds the original agent for confidentiality; cross-agent
result reuse and tenant result sharing await caller integration. Strict
fingerprints include full code/binding scope. Argument keys remain governed tool
payload values; reservation/audit key metadata is hashed. Key tombstones do not
purge task/result/dispatch payloads. Consumed terminal work remains unknown until
complete effect-aware finalization; success reports alone cannot rotate windows.
Tombstones/history remain indefinitely.

The current knowledge run uses scripted Control. S17's actual development-stack
result remains separately pinned historical evidence; no new full-stack image or
production SLO qualification is claimed for S08. Complete crash/partition,
consent, generalized effect and release matrices remain outstanding.

The fixed baseline credits 12/33 stages: **28.0% complete / 72.0% pending**,
rounded. All seven full phases remain unaccepted. Next: **S09, whole-invocation
rebinding**, then S10 and subsequent outstanding stages under the same focused
change/test/fix and one-stage-commit workflow.

## S09 — Whole-invocation rebinding (delivered)


ADR 047 defines original-deadline recovery with immutable predecessor history.
Implemented scope now includes authoritative complete-journal proof, admission-time
Python OCI device/pool/image qualification, atomic recovery records and SQL
transition guards, archived lease/dispatch pointers, fresh execution and released
effect-generation authority, public replay activation and stale-receipt fencing.
Current grants, credentials, epoch, approvals and cancellation remain mandatory.
Denied approval decisions cannot reopen through the generic FAILED state.

Validation passes all 102 original focused cases with no skips, plus
the expanded 11-case recovery integration suite on SQLite/PostgreSQL. The full
enterprise suite now passes 254 cases with no skips. Coverage includes complete
proof/qualification, immutable scope/records, rollback, signed issuance,
canonical-key replay, competing Control instances, current authority, denied/live
approvals, renewed-credential deadline refusal, old nonce rejection, deadline
extension guards and retained late reports. Generation 2 also completes through
Control claim consumption and the actual JDBC target SDK with one database
mutation; duplicate claims and the released predecessor cannot apply it again.
This is a component integration test, not a new actual memory/RAG full-stack run.

Rust workspace tests, formatting and Clippy pass. CI regressions pass 380 tests
with 17 explicit environment-dependent skips; 1129 generated bindings and the
structural corpus are unchanged. Contracts pass 153/155 (two skips, including the
separate PostgreSQL migration specification gate); Python SDK passes 76 with no
skips. The full javaCheck gate passes with real PostgreSQL: 254 enterprise
cases with no skips, and 267 main Control cases with zero failures and five
documented skips (benchmark, optional Redis and three optional JSON SDK workers).
All three migration specification cases pass against SQLite and PostgreSQL with
no skips. The foundation Python group passes end to end with Docker-backed tools:
289 v1 and 250 v2 PHP round trips/negatives, every generated PHP syntax check,
Helm lint and Gateway/Control/endpoint/fleet install/upgrade/schema/negative checks.
ServiceMonitor core-schema skips are covered by the dedicated monitor validator.

The six-test real rootless OCI gate passes in isolation. The first concurrent
run passed five checks but timed out waiting for the broker cancellation/deadline
route; the complete isolated rerun passed without code or timeout changes. No
production load/SLO claim follows from the rerun. Evidence records known parent
508e512385ab8ab996909dee40650a3643d25415, source
33223fbdfcd43737050abfc7a0ae176d44c8f63663aff8071290a65124fd3189,
x86_64, I/O device 8:80 and Python image
docker.io/library/python@sha256:aa943ce411423c7582288f18707ff66eddcbaeb510502d747a72aeb34f30d159.
Only documentation/progress changes follow that run; the execution-source
inventory (2387 Java/Rust/SQL/JSON/Python/TypeScript/PHP/Gradle files, sorted
path + NUL + binary file SHA-256) is
5a4313604de47ce3bd1744098ef0b1222c80a9067484cdcd392463b49ab016e3.

The knowledge verifier's --verify-only gate also passes against the previously
executed 32-case component report and unchanged target/verifier/SDK/contracts
artifacts. This verifies existing evidence; it does not reexecute those tests or
claim S09-qualified current Control/Gateway deployment evidence. The revision-10
proposal and shipped V1–V5 migrations remain byte-for-byte preserved; the
unshipped kill-switch specifications move unchanged to V7.

S09 earns its fixed phase-1 stage weight: the authoritative delivery total is
**30.9% complete / 69.1% pending**, with 13/33 stages Done and 0/7 full phases
accepted. The baseline scope/weights and denominator remain unchanged.
Consumed recovery is default off and requires independent operator qualification;
a pin is not remote attestation or acceptance of complete phase 2/3. General
routes, broker credential separation and aggregate caller finalization remain
later-stage work. See [operation recovery](../control-plane/operation-recovery.md).

Next: **S10, complete operation finalization and callers**. Aggregate every
attempt/effect and late evidence, expose honest caller history/outcomes and
execute the remaining transitions/audit-spool/duplicate-execution acceptance.
Push this complete S09 code/test/documentation/progress change before starting S10.


## S10 — Complete operation finalization and callers (amended stage delivered)

The entries below preserve the incremental implementation record. The final S10
acceptance checkpoint at the end supersedes their earlier in-progress statuses.

ADR 048 defines independent operation-wide derivation, sealed manifests,
settlement, witnessed receipts/equivalence and honest caller history. The first
focused change implements the pure policy over all attempts and generations;
no runtime/wire integration is claimed yet. All 16 focused policy cases pass
with no skips. They cover old/new attempts, active and partial writes, missing
or unsealed history, settlement, fixed/read-only plans, matching and conflicting
receipts, explicitly assumed approved deduplication, witnessed closed-authority
equivalence, receipt contradictions and a 4096-effect chain without recursion.
The implementation uses iterative bounded graph traversal; missing references
and cycles cannot establish completion. A response
digest is explicitly not a business receipt. Unimplemented fixed-plan consent
and target-guarantee sources must stay unavailable, not inferred from a label.

Next: focused original-plan transition cases and fixes, then authoritative SQL
manifest/settlement/finalization evidence and caller contracts/integration.
S10 remains Partial with no earned weight: **30.9% complete / 69.1% pending**.
No phase or additional security claim is accepted by this initial policy code.


S10 focused command: Gradle enterpriseTest filtered to
io.ololabs.toolgate.control.application.OperationFinalizationPolicyTest, over
known delivered parent 1ed49361. These are internal policy tests, not executed
SQL/caller integration or a full-stage/security acceptance result. No S10 commit
is made before its complete implementation and applicable thorough verification.


S10 canonical-contract increment adds separate derived state/evidence grade,
bounded attempt/generation history and an optional invocation response projection.
Stored invocation constructors retain their legacy Java signature; raw execution
state remains separate. History excludes bearer capabilities, target claim tokens
and payloads. Contract generation and focused schema checks are the next gate;
the projection is not yet populated by runtime code.


S10 manifest-storage increment adds immutable authorization membership and
closed-authority manifest seals in new V7 migrations. Generation insertion is
independently refused for a sealed or inactive attempt; membership is captured
with the generation transaction. Existing generations backfill membership only,
not invented historical seals/settlements. RLS, runtime privileges and bounded
tenant-capacity accounting are included. Future kill specifications move unchanged
to V8. Focused SQL/store tests are next; no passing migration evidence is claimed
for this increment yet. Overall credit remains 30.9%, and S10 remains uncommitted.


S10 focused manifest/store checks pass for actual SQLite/PostgreSQL membership,
closure, rollback, immutable runtime privileges and refusal of empty/malformed
proof. Existing effect-ledger and signed-recovery integration scenarios also pass,
as do focused SQLite initialization and tenant-RLS checks. The next correction
serializes PostgreSQL insertion/sealing on the same attempt and independently
guards first dispatch/consumption against sealed/inactive authority. Its rerun is
pending; runtime automatic closure, settlement, derived persistence and callers
remain outstanding. No additional stage weight or S10 commit is claimed.


The first PostgreSQL lock rerun refused row locking on immutable attempts because
the runtime role intentionally lacks UPDATE there. The lock now uses the existing
mutable operation row, serializing insertion/sealing without broadening runtime
privileges or weakening immutable attempt guards. The SQLite checks remain passing;
the corrected PostgreSQL/legacy effect/recovery rerun is next.


The corrected operation-row lock rerun passes, including actual PostgreSQL runtime
roles, existing effect and signed-recovery cases, SQLite initialization and tenant
RLS. No privilege expansion was needed. The next increment closes known attempt
manifests atomically with terminal invocation transitions and strips derived
response metadata before storage. Its rollback and integration checks are running.
Settlement, immutable derived outcomes and caller behavior remain in progress;
S10 is still Partial and uncommitted.


S10 settlement increment adds immutable Control proofs for expired never-consumed
attempts and qualified broker-only attempts whose generations are all released or
independently target-confirmed. Qualified settlement requires the current endpoint
and directory device enabled at capture; it cannot rely on a disabled host's
report. Manifest closure ends only its matching lease, never a replacement's lease.
Receipt/source digests remain metadata, not execution authority. Focused settlement
validation is next; no additional progress weight or passing result is claimed yet.


S10 focused settlement validation passes on SQLite and actual PostgreSQL with no
skips: never-consumed settlement waits for expiry; wrong proof is refused; signed
qualified terminal attempts close their own lease; released writes may settle;
SENDING and target-consumed pending writes cannot settle before independent target
resolution. Disabled endpoint/directory state refuses settlement. A later target
contradiction still becomes CONFLICT despite a retained settlement. Tenant-RLS and
the 16 derivation cases also pass. Next: authoritative fact projection, immutable
derived snapshots, caller history and integration. S10 earns no weight yet.


S10 authoritative fact projection now reads bounded nonce/attempt bindings,
manifests, all effect generations and independently authenticated target evidence.
Automatic preparation closes only inactive attempts and records eligible Control
settlements; no caller/executor proof flags are accepted. Receipt identity uses
the authenticated shared target claim, never an output digest. Fixed-plan and
generic equivalence sources remain unavailable until their owning features ship.
Runtime response projection, immutable finality snapshots and caller integration
remain outstanding; the new reader/preparation tests are next.


S10 settlement/fact validation now passes 34 focused enterprise cases and five
actual tenant-RLS cases with no skips. The next integration cases exercise all
recovery generations, RUNNING before closure, target conflicts after settlement,
unqualified reported success, unresolved sends and historical proof after device
revocation. Qualified proof capture now takes shared endpoint/directory locks so
concurrent disablement cannot race its trust check. These new cases are running;
derived persistence and caller integration are still outstanding.


S10 bounded authoritative fact/preparation integration passes on SQLite and
PostgreSQL: all recovery generations contribute; target completion stays RUNNING
until worker closure; unqualified success and unresolved sends remain unknown;
historical captured settlement survives later device revocation; a target conflict
still requires reconciliation. The next increment adds immutable derived snapshots
with independent finality guards and blocks execution when reconciliation is
required. Its validation is pending; no caller projection or stage completion is
claimed yet.


The S10 outcome-storage migration passes the focused SQLite/PostgreSQL manifest,
recovery and derivation suite. The next increment persists monotonically revised
derived outcomes with immutable SQL guards, latches reconciliation, permits only
ADR 047's recorded FAILED-to-RUNNING recovery exception, and writes matching audit
inside the same transaction. Repeated unchanged observations reuse the existing
snapshot. A terminal report's closed execution lease no longer blocks current
authorized result reads solely because that terminal attempt ended its lease.
Persistence, audit rollback, stable-read and recovery/final-conflict integration
checks are running; their results are not yet claimed. Caller activation/history,
business-key projection, reconciliation and full stage conformance remain
outstanding. S10 is uncommitted and earns no weight: 30.9% complete, 69.1% pending.


S10 immutable snapshot integration passes the focused SQLite/PostgreSQL suite:
repeated unchanged evidence reuses its revision, audit/outcome/settlement roll back
together, recorded recovery can reopen FAILED to RUNNING, and late target conflict
moves a successful snapshot to reconciliation. Caller increments now expose the
derived outcome separately on authorized invocation reads and add bounded history
with revision-pinned effect pagination and no raw claim/dispatch credentials.
A COMPLETED transport report with an error result frame cannot provide successful
attempt evidence. These caller/error-frame checks are running. New execution
checkpoints also derive and refuse reconciliation; nonce SQL guards independently
refuse known reconciliation/conflict. That newer fencing increment still needs
its focused rerun. Current progress stays 30.9% complete / 69.1% pending; caller
rollout, reviewed reconciliation, business-key integration and stage conformance
remain outstanding. No S10 commit or push is claimed.


The first fencing rerun found test assumptions requiring correction: heartbeat
uses the canonical cancelRequested field, and rollback must preserve the existing
RUNNING snapshot now created by execution checkpoints. The following rerun also
confirmed that ending the lease makes the watchdog retain OUTCOME_UNKNOWN on a
late worker success; the test now requires that preservation instead of promotion.
The corrected fencing suite is still pending.

Gateway/MCP integration now reads authorized Control outcome metadata after a
reported result, keeps it under the Gateway's reserved metadata key, and marks
non-SUCCEEDED business outcomes as tool errors. Missing/unavailable projections
fail closed with OUTCOME_UNKNOWN and retryable=false; worker structured content
cannot populate the trusted metadata. Control admission/replay also returns its
derived projection without changing the stored executor report. Gateway unit
validation and a corrected Java rerun are next; these changes are not yet claimed
as passing or delivered. S10 stays Partial, uncommitted and unpushed.


Gateway's 20 focused library tests pass, including reserved metadata replacement,
unknown business outcomes and preservation of independently reported errors.
The first broader Gateway run found ten mocks without the newly required outcome
read; the mock authority now implements that port and explicitly reports generic
unqualified success as OUTCOME_UNKNOWN. Integration cases additionally require
unavailable outcome reads to withhold worker content and refuse automatic retry.
The complete Gateway rerun is running. Operator documentation now describes the
outcome/history endpoints, cursor conflicts, evidence grades and compatibility.
Released-only generations are graded CONTROL_PROVEN, never TARGET_CONFIRMED;
the new regression assertion needs the next Java rerun. No S10 delivery credit
or stage commit is claimed.


Gateway complete component verification passes, including its integration tests
after upgrading the fake authority to the new metadata port; the generic mock
keeps worker success separate from an unknown business outcome. The current Java
caller run found four comparisons mixing response-only projection with preserved
stored reports. Tests now compare the complete raw report separately and assert
the independent unknown/nonretryable projection. A corrected focused rerun is
running; no application authorization/finality guard was weakened. A new
history-specific current-authorization/denied-read rollback test needs the
following rerun. This source remains based on tested parent 1ed49361; no invented
new commit SHA or completed-stage weight is recorded.


The corrected 139-case Java focused caller/fencing suite passes on SQLite and
actual PostgreSQL, including signed public recovery, active conflict cancellation,
late-report preservation, bounded history and stable derived reads. A subsequent
business-key increment now synchronizes key outcome from the validated immutable
projection, preserving identity/ownership/retention and independently checked SQL
finality. Worker terminal reports and authenticated late target outcomes refresh
the projection in the same audited transaction. Generic unqualified consumed
success therefore cannot retain a COMPLETED business-key outcome. Existing
CANCELLED/EXPIRED/REJECTED no-write reasons are preserved for compatibility;
partial or unresolved business outcomes remain nonretryable/OUTCOME_UNKNOWN in
the existing key enum. These newer changes and the history-specific revocation
case require their focused rerun. S10 remains Partial and uncommitted.


The first business-key rerun exposed PostgreSQL's requirement to type a nullable
parameter used only by IS NULL; the query now casts that placeholder as TEXT.
Automatic terminal settlement also required moving the disabled-device test's
disablement before closure, and the rollback test now rolls back an actual
terminal transition plus settlement/outcome/audit rather than a no-op read.
The next run passed those paths but found a test key reused by the combined
PostgreSQL policy fixture; the new regression now uses its own key. The final
focused results for this increment are pending. No guard, timeout or privilege
was relaxed.


The dedupe-window integration now refreshes the complete derived outcome before
rotation and accepts only verified SUCCEEDED/FAILED. SQL exact-archive and
retention guards also recognize the latest derived successful/failed snapshot
while vetoing reconciliation. The original never-consumed failure proof remains
valid for existing maintenance paths; this does not change ownership, fingerprints,
window duration, retention or archive identity. Focused rotation/retention tests
are next; no new passing result or stage weight is claimed.


Console integration adds canonical OpenAPI history discovery and regenerated UI
operation bindings. Inspection labels the legacy fields as reported state and
reported resources, fetches authorized derived outcome/evidence grade and bounded
attempt/effect history, and restarts stale pagination through a read-only refresh.
The new console test requires reported SUCCEEDED to remain distinct from an
OUTCOME_UNKNOWN business outcome with automatic retry disabled. UI checks are
pending. The remaining PostgreSQL policy-fixture failure was caused by running a
new admission case after the prior case intentionally revoked its grant; the new
case now uses the existing fresh-database scenario runner. No production grant
check was relaxed.


The five focused window/policy/revoked-history JUnit cases pass, including their
fresh PostgreSQL scenario matrices and SQLite positive derived-success rotation.
Verified completion can rotate the optional dedupe window even while the legacy
summary conservatively remains unknown; unqualified reported success keeps the
business key unknown. The console's shared authorized history view now also
appears beside human test-tool responses, distinguishing executor-reported
success from business completion. Its focused UI regression run is next.


The native human/local result contract now adds an optional separate derived
operationOutcome, retaining the previous two-argument Java response constructor.
Current-authorized result reads do not renew an ended lease or supply execution
authority; reconciliation fences effects while reported results remain accessible
with their separate outcome grade. Canonical schema precedes regenerated bindings.
This latest native-result increment and its compatibility/runtime tests are next;
no passing result is claimed yet.


Native human/local result tests and contract checks pass; the Gateway complete
component suite also passes after the optional response projection upgrade.
The shared console history view passes all ten focused outcome/human-tool cases.

Plan review confirmed that PARTIAL must be a distinct business-key outcome,
rather than remaining unknown merely because the previous enum lacked it.
V7 now widens the PostgreSQL CHECK and atomically rebuilds the SQLite key table's
CHECK while preserving every value, index and existing guard. Projection mapping
now records PARTIAL one-to-one; no stage scope or weight denominator changed.
Data-preservation/migration and partial-outcome regressions are next. Optional
window rotation remains restricted to positively resolved SUCCEEDED/FAILED;
a partial operation is not silently resent by this change.


The nine focused canonical-contract/SQLite-upgrade tests pass, including exact
retained key values, retained indexes/guards, foreign-key checks and atomic
rollback after a deliberate failed V7 upgrade. ADR 016 now explicitly amends
business-key finality/PARTIAL mapping. The next runtime regression uses trusted
admission/target ports with real SQLite/PostgreSQL storage: a completed write plus
worker failure must produce a PARTIAL key and TARGET_CONFIRMED grade; late
contradictory target evidence must change it to unknown/reconciliation without
changing ownership, fingerprint or retention. This is component evidence, not an
OCI/provider qualification. Its runtime rerun is next.


The real SQLite JDBC migration run caught comment-only statement sections in
the new rebuild prelude, which Python's executescript accepts but the JDBC runner
cannot execute. The delimiter placement is corrected without changing the runner
or data guards. The driver-level SQLite/PostgreSQL partial-key rerun is pending;
the earlier Python preservation evidence alone is not treated as sufficient.


The real JDBC/PostgreSQL PARTIAL migration/derivation rerun passes after the
delimiter fix, including SQLite initialization/reopen. The RLS filter in that
command was misnamed; new-migration RLS is rerun with TenantRowSecurityTest below.
The next increment captures immutable execution-report qualification from consumed
admission scope plus current endpoint/directory trust. Old reports default to
unqualified; re-enablement cannot upgrade a recorded report. Bounded valid late
complete results can contribute reported attempt success after closure while
pending effects, missing scope and finality guards remain binding. The current
invocation/dispatch pointer is preserved. This increment is not yet validated;
late-report and disabled-device regression tests are next.


Late-report regression cases are authored for genuine complete worker evidence
after watchdog closure, refusal to settle its still-pending write, later target
completion, raw-invocation preservation and current-credential caller history.
A second case retains a disabled-device report as unqualified and proves later
re-enablement or duplicate delivery cannot promote it. These are trusted receipt-
port component tests; public disabled-device/spool ingress is not claimed by them.
Attempt history separates qualified late reported state from independently graded
effect/business outcome. Focused SQLite/PostgreSQL execution is next.


The first late-report run correctly refused SUCCEEDED at the independent SQL
guard because it recognized only a successful attempt snapshot, not the newly
qualified immutable late success. The guard now accepts that exact complete,
non-error late report only when joined to the same settled attempt; all existing
closed-ledger, resolved-effect and finality checks still apply. Forty other focused
cases passed; the corrected late-report rerun is pending.


The next S10 increment adds the plan's human attempt-scope settlement through
the existing maker/checker flow. Canonical attestation names exact attempts and
the expected derived evidence revision; it requires independently reviewed
no-further-effects evidence and closed, already resolved ledgers. It cannot
invent a target receipt, promote worker state, clear pending/conflicting effects
or reopen authority. Both reviewers must be distinct and independent of owners.
ADR 048 records this decision before implementation. Storage, runtime and UI
integration/tests remain pending; no passing evidence or weight is claimed.


The corrected late-success SQL guard allowed target-backed completion. The
remaining test failure was the caller reusing its original 15-second fixture
credential after a 30-second watchdog simulation; ordinary admission correctly
denied it. The test now supplies an explicitly renewed context through the normal
human admission path before reading history. The deadline check stays unchanged;
the rerun is pending.


The corrected late-report suite passes all 42 focused JUnit cases, including
actual PostgreSQL runtime-role qualification/closure and renewed caller history.
The human-review increment now captures immutable original ownership at new
admission so a later agent-owner transfer cannot make the original owner an
independent reviewer. Legacy direct-human operations retain their immutable user
identity; legacy agent operations lacking an original snapshot cannot acquire
this proof from current ownership alone. This new storage/admission increment
still needs focused verification; human review records/runtime remain pending.


Original-owner capture/transfer component cases pass on SQLite and PostgreSQL.
The accompanying standalone RLS task failed because its test filter named a
nonexistent class (TenantDatabaseRlsTest). No passing new-owner-table RLS result is
claimed from that task; the actual TenantRowSecurityTest suite is being executed
now. Earlier historical RLS evidence remains specific to its recorded revision.


Human attempt-review storage now binds an approved exact revision to the sealed,
closed, resolved consumed attempt and captures immutable review/evidence digests.
Its new tables have tenant foreign keys, forced PostgreSQL RLS, SELECT/INSERT-only
runtime privileges, immutable rows and capacity accounting. Derivation recognizes
that distinct proof source and grades a final outcome REVIEWER_CONFIRMED; it does
not rewrite executor state or invent target evidence. Storage verification and
the current-authority maker/checker runtime integration remain pending.


Existing maker/checker proposal history now gains SQL guards: requests, principal
identity and digest cannot change, records begin pending and have one terminal
decision, and deletion is refused. This prevents a later mutable proposal from
silently replacing the source of an immutable attempt-review proof. The previous
original-owner increment passed the correctly named TenantRowSecurityTest suite;
the newer review table/guards still require their own runtime-role verification.


Seven review-storage JUnit cases and five actual tenant row-security cases pass,
including the PostgreSQL scenario matrix, rejection of pending/stale/ambiguous/
owner-derived proof, immutable approved source/proof and no executor-state rewrite.
The public maker/checker integration now requires both reviewers' current scoped
permissions, distinct identities and original/current-owner independence; it
checks the exact evidence revision again on approval and derives the result in
the same transaction. Exact duplicate decisions return their recorded decision
without another proof/audit. New evidence rejects stale approval; stale denial
can close the proposal without settling anything. Legacy resource-only review
does not implicitly supply complete attempt-scope proof. Runtime tests are next.


Public runtime review checks pass on SQLite/PostgreSQL: independent current
maker/checker authority, stale-approval refusal, safe stale denial, original-owner/
owner-super-admin exclusion, exact duplicate decisions and full rollback of an
approved state mismatch. Human scope proof stays separate from executor state.

The console now offers explicit no-further-effects attestation only for closed,
unsettled consumed attempts with complete resolved ledgers. Proposals bind the
displayed evidence revision and exact attempt IDs; reviewers see that scope.
Refreshing inspection clears the attestation. Business PARTIAL review may describe
completed effects even when legacy resource-count semantics differ; actual
derivation must still match before the transaction can commit. SQL also checks
current reviewer users/agent owners. These latest UI/guard changes need their
focused rerun.


Native caller metadata work adds fresh-context status/history reads: Control
binds current verified caller identity to the original operation, rechecks current
workload proof/grants and exposes retained metadata without renewal, resend or a
permit. Historical execution deadline/credential expiry does not become a blanket
metadata-read denial when the same owner presents valid current authority.
Unknown/unowned operation IDs remain not found. Original execution context stays
unchanged. Gateway routing/ports and focused current-authority tests are next.


Gateway native GET status/history routes now forward the authenticated current
caller context to metadata-only Control endpoints. MCP's post-result lookup uses
that same context-bound path. Read input is bounded; responses must retain the
requested operation ID. RUNNING remains running rather than being translated to
uncertain execution. These new ingress/relay and Control changes require focused
runtime, ownership/revocation and wire tests; no new passing result is claimed.

### S10 current caller metadata and conformance checkpoint — 2026-11-02

Known tested parent remains 1ed49361. S10 is a working-tree change, not a committed
delivery. Weighted progress stays **30.9% complete / 69.1% pending** (13/33 stages;
0/7 full roadmap phases accepted). The S01–S33 denominator/scope has not changed.

Fresh native Gateway status/history APIs now forward a verified current caller
context to role-restricted Control metadata endpoints. Control matches original
ownership/binding, checks current directory proof and grants, and retains original
execution deadlines and credentials. Five focused Java caller scenarios and their
PostgreSQL scenario matrix passed, including expired execution, rotated credentials,
revoked grants, uniform unknown/unowned refusal and the configured verified-human
issuer. Gateway's updated suite passed: 20 library, 41 core, one relay-contract and
seven verified-user credential tests, with no failures/skips. These are component
checks, not distributed deployment acceptance.

Eleven new explicit Control component vectors in the original transitions,
audit-spool and duplicate-execution catalogs cover aggregation, qualified late
success, unresolved reported success, partial error results, atomic outcome/audit,
immutable closure, review rollback, no blind retry, settlement, review replay and
post-deadline metadata reads. The new source-bound runner owns a PostgreSQL fixture,
reruns selected classes and refuses missing/duplicate/failed/skipped executions or
source changes. Its two verifier tests and all 13 conformance-catalog tests passed;
the runner itself has not executed yet. Catalog labels remain authored, and the
diagnostic scorecard still promotes no cases or claims. The security/integration
inventory now contains 220 cases (209 previous plus 11 new component vectors).

A broad contract run exposed four stale test-fixture assumptions: the new canonical
schema raises the file count from 18 to 19; effect-generation fixtures must bind
their consumed nonce, consumption time and current authorization epoch; execution
evidence inserts must name legacy columns after qualified_report was added.
Focused corrected runs pass: both effect-generation tests, the SQLite lease/
evidence test (PostgreSQL counterpart not executed in that command), and the
foundation suite. Canonical bindings (1,148 files), UI OpenAPI bindings and
git diff --check also pass. The original broad run is not recorded as passing.
Full javaCheck/PostgreSQL migration checks and Rust workspace gates are in progress.

Next: fix any stage-wide failures, execute the new component-vector runner, qualify
the frozen current source and target artifacts, and complete the original
distributed conformance gaps. Those older suites include task input/cancel,
spool exhaustion/fenced recovery and crash/redispatch assertions that the focused
finalization tests do not establish. No whole-suite acceptance, full-stage weight,
intermediate stage commit or push is claimed.


S10 verification follow-up: full javaCheck passed (86 tasks; 16 executed) and all
three migration-specification checks passed, including PostgreSQL and SQLite.
The all-contract run executed 165 tests with four fixture failures and two
environment skips; the four failures were fixed and their focused suites passed.
A subsequent complete UI run passed all 240 tests with coverage, build and nine
browser checks after limiting workers to two. The first broad UI run under
concurrent load had 16 timeout/element-wait failures; timeouts were not increased.

Rust workspace tests passed. Clippy caught the new synchronous HTTP metadata
helper returning a large Response error value; boxing that error fixed the warning.
The affected 41 Gateway core tests and workspace/all-target Clippy then passed.
PHP passed 298 v1 and 250 v2 model round trips/security negatives, plus source
syntax checks. Python SDK passed all 76 tests. CI ran 380 tests with two release
fixture errors and 17 environment skips; adding the new schema files to Git's
intent-to-add index made the tracked-source fixture copies complete, and both
affected groups (25 release and eight manual-source tests) passed. Source-header,
license and obvious-secret checks passed after indexing. No commit was created.

The new eleven-vector Control runner passed with its four required PostgreSQL
scenario methods and a frozen source SHA-256 of
26794e57a5aab80fa655814db30c3567eeada2836cfaa6df3f63210476e1cfbc
(parent 1ed493617dcb580f8d77b6ef2682ee76df786e04). This is a known tested working-tree
source, not a new commit SHA or current-source acceptance after later edits.
Its first invocation could not create an output directory under a root-owned
build/security directory; the successful run used an owned temporary output path.

Review after that run found a bounded-history ordering gap: exceeding the generation
limit returned UNKNOWN even with known active work. The next focused correction
preserves RUNNING at attempt/effect/generation caps, keeps inactive clipped history
UNKNOWN and retains witnessed-conflict priority. Its focused test and renewed
source-bound qualification remain pending.

Eight older authored conformance cases were also aligned with revision 10:
executor notStarted/BUSY, tool labels and lack of a reply are never no-write proof;
late evidence does not overwrite current attempts, and independent contradiction
requires reconciliation. This changes authored expectations, not acceptance labels,
stage weights or the preserved plan. Original distributed suites remain unaccepted.


### S10 final acceptance checkpoint — 2026-11-02

**Done under the explicit scope amendment above. Weighted progress is now
33.9% complete / 66.1% pending (14/33 Done); 0/7 full phases accepted.**
No stage/phase weight or denominator changed. The original distributed suites
and independent future authority sources remain required; the amendment changes
which bounded S10 delivery earns its existing 7/6 planning weight.

Delivered: canonical derived outcome/history/review contracts and regenerated
bindings; new V7 on both dialects; immutable attempt membership/seals, positive
automatic/human settlements, review-owner capture, qualified late evidence and
derived revision/finality; PARTIAL business-key integration; current-authority
metadata/history; conflict fencing; Gateway/MCP/native/human and console
presentation. Legacy worker state remains reported evidence. Missing/truncated
history and unavailable future authorities never establish success or retry.
Known-active capped journals stay RUNNING; inactive clipped history stays unknown.

Executed acceptance and regressions:
- Full javaCheck passed; Control's main task executed 267 tests with five existing
  environment skips. The final source-bound runner reran five Control classes:
  **143 tests, zero failures/errors/skips**, including all **17 policy cases**,
  the eleven mapped finalization vectors and all four required PostgreSQL scenario
  methods. Finalization source SHA-256:
  **29a7d82b8ccee52e9ca3b727cf751e6cb55cc8e9daa113a859d6d14ce2795a7a**.
- PostgreSQL/SQLite migration specifications, the dedicated lease/evidence storage
  checks (including the previously skipped PostgreSQL counterpart), and SQLite
  V6-to-V7 key preservation/rollback checks passed. Original V1–V6 migration bytes
  and the revision-10 proposal remain unchanged.
- Rust workspace tests and formatting passed; the affected Gateway core rerun
  passed all 41 tests and workspace/all-target Clippy passed after the error-boxing
  fix. Actual isolated OCI qualification passed **six tests**, including restart
  reconciliation, actual pinned SDK dispatch/revocation, private worker identities,
  flooding, filesystem/ambient-authority denial and teardown.
- All **240 UI tests**, coverage, TypeScript checking/build and **nine browser
  checks** passed with two UI workers and unchanged timeouts. Canonical/structural
  bindings (1,148 files), contract fixtures, both conformance verifier tests and
  all 13 catalog tests passed. PHP passed 298 v1/250 v2 round trips and security
  negatives; all **548** PHP source files passed individual syntax checks.
- Python SDK: **76 passed**. The 165-test broad contract run's four fixture failures
  were fixed; affected foundation/storage groups passed. The 380-test CI run's two
  release-copy errors were fixed by intent-to-add indexing and affected groups
  (25 and eight tests) passed; its 17 platform/environment skips remain disclosed.
  These are broad-run-plus-focused-fix results, not invented clean rerun totals.
- Actual memory/RAG component qualification rebuilt the current Java SDK/contracts
  and passed **32 tests** (core five, memory ten, RAG seventeen), with TLS, vectors
  and evaluation enabled. The source remains olo-mono revision
  **8e3f7a7141119b800cf10248ab2e2298bc8587dc**, digest
  **9552f0a7fecaf65c420540126beeb7e127efaea53c53c45662c676ba8ffc094c**.
  Control is scripted in this component gate; the older S17 development-stack
  image is not claimed as a qualification of current S10 code.
- Final source-header/license/obvious-secret checks, UI operation bindings,
  generated bindings, preserved-plan comparison and diff whitespace checks passed.

Known tested ToolGate parent:
**1ed493617dcb580f8d77b6ef2682ee76df786e04**.
The isolated OCI run tested working-tree source
**5a67d530e2fc42b4bdb10357b1eaf3be974ad626ef5a9ca851540cfa05246990**
on x86_64, pinned Python image
sha256:aa943ce411423c7582288f18707ff66eddcbaeb510502d747a72aeb34f30d159.
Later edits record this acceptance/progress only; neither digest is an invented
SHA for the implementation commit. Original distributed suites and all C1–C8
claim checks remain unaccepted in the diagnostic scorecard.

This progress update belongs to the same single S10 implementation commit as
code, tests, migrations, contracts and operator docs. Push it to
feature/zero-trust-execution-plan before beginning **S12: trusted broker OS/network
separation**. Full phase/security acceptance and production readiness are not
claimed.


## S12 — Trusted broker OS/network separation (in progress)

Known parent: **115f4013** (the single S10 code/progress commit, pushed to the
requested feature branch before S12 began). Overall remains **33.9% complete /
66.1% pending**; Partial S12 earns zero weight and no full phase is accepted.

ADR 049 records the intended per-invocation broker/container and restricted
supervisor IPC/transport boundary. The first focused change removes the broker's
general Control field in favor of a narrow supervisor authority port. Its trusted
scope fixes operation, execution fence, deadline, profile/digest, broker owner,
routes/destinations and declared secret names. Closed/expired/foreign scopes,
changed profile/route/destination/method/digest and undeclared secrets are refused
before Control contact. A bounded local handler ledger records issued grants;
unknown/borrowed grants and repeated send transitions are refused. A lost/refused
Control response does not reset the reserved handler or permit blind replay.

Executed: cargo fmt and four focused authority-port tests passed. They cover
scope substitution, expiry/closure, exact manifest binding, undeclared secrets,
borrowed grants, repeated/lost-response handlers and the 256-handler cap.
These are pure component checks; broader Tool Host regression, actual denied
Control/target contacts and OS/network qualification are still outstanding.

The broker still runs inside the supervisor. No isolated broker, new admission
qualification, C1/C5 guarantee, completed S12 weight, intermediate commit or push
is claimed. Next: run affected Tool Host broker/dispatch regressions, then add
bounded private IPC and immutable transport delegation, separate broker execution,
coupled teardown/recovery and the actual identity/namespace/impersonation matrix.
Code, tests, operator documentation and this progress update must be committed
together only after the full S12 stage passes.


S12 focused follow-up: five authority-port tests passed, including a real HTTP
Control fixture proving one allowed secret call contacts Control and the subsequent
foreign/closed/undeclared/borrowed-grant calls do not. Tool Host dispatch (13 passed,
one actual-OCI test intentionally ignored in this ordinary command), HTTP/1.1
egress (one) and actual mTLS send-gate/target-contact regression (one) passed;
Tool Host/all-target Clippy passed. The ignored OCI case must execute explicitly
with the full separated-broker qualification before stage acceptance.

The private IPC increment adds closed typed policy/authority/response envelopes,
separate worker and privileged message shapes, operation/broker-bound readiness,
monotonic single-outstanding sequencing, call/authority limits and bounded newline
I/O (4 MiB/frame, 4,096 frames and 64 MiB per direction). EOF, malformed/oversized
input, encoding/I/O failure and budget exhaustion poison the channel without retry.
The policy carries no Control URL, workload key/certificate or host/runtime path.

Its first focused run exposed serde's internally tagged unit variants accepting
extra fields despite the enum-level deny_unknown_fields. Empty struct variants
now reject those fields; explicit CLOSE/SENDING/REFUSED/UNAVAILABLE injection
regressions pass. Clippy then required boxing the large authority envelope.
The final **nine IPC tests and all-target Clippy passed**, covering those refusals,
credential/path exclusion, duplicate fields, split frames, truncation, counts and
read/write budgets. This codec/session is not yet wired to a separate process.

The next small correction narrows brokered credential names to auth declarations
actually used by the invocation's granted routes, plus its declared tool secrets;
its focused test is pending. No S12 commit, separation guarantee or extra stage
weight is claimed. Process launch, immutable transport delegation and coupled
worker/broker teardown/restart qualification remain outstanding.


S12's granted-route credential-name correction passed all six authority-port tests.
Further review found that the old broker accepted HTTP 200 from the send-gate
without checking its authorized boolean. The new supervisor port now requires
authorized=true, rejects empty/oversized issued grant values and burns the handler
before awaiting Control. The actual mTLS target regression is being extended to
cover HTTP 403, HTTP 200/authorized=false and a genuine allowed send; its rerun is
pending. These are incremental fixes inside S12, not a completed stage or a
separate commit.


S12 immutable-transport increment passed **13 focused IPC/transport tests** and
all-target Clippy after initializing the crypto provider in the standalone test
client fixture. The first helper run also rejected the generic fixture's larger
route ceilings; that fixture now explicitly uses the proposed separated tier's
**2 MiB request/response ceiling**. A dedicated over-limit test verifies refusal,
rather than silent response truncation. Private framing remains 4 MiB and headers
64 KiB. These constraints are not yet enabled as an operator runtime tier.

The helpers preserve the exact canonical request fingerprint across buffering and
reconstruction, reject body/recipient/path/query/method/host changes and hidden
framing headers, and require the supervisor's exact issued service credential.
A consuming transport object exposes no mutation/clone path. A real target test
received the exact bound body/credential once.

The actual broker now prepares without a workload identity. Only the supervisor
authority/transport implementation builds the target client from its captured
destination/address policy and owns mTLS identity. A bounded per-handler state
machine distinguishes pending issuance, issued, checking a send gate, positively
allowed and sent. A false/error/lost send-gate result cannot authorize transport;
exact request digest and one transport owner are required. Source records still
derive from Control/target witnesses, not these local flags.

Executed after that refactor: **51 Tool Host library tests passed** (one separate
OCI case ignored in the ordinary command), **13 dispatch tests passed** (one OCI
case ignored), HTTP/1.1 egress and the expanded real mTLS send-gate test passed;
all-target Clippy passed. The new tests cover a concurrent eight-contender
single transport owner and closed/foreign/replayed buffered transport. No OCI
process/broker separation acceptance is inferred from these component tests.

The owned parent channel then passed **18 focused IPC/transport/channel tests**
and all-target Clippy. Its delegate supplies both policy and authority, preventing
a caller from pairing another operation's context with a different authority.
Readiness, worker/authority replies and monotonically numbered actions must match;
replay, rewritten trace prefixes, a silent broker and stalled authority fail
terminally. Timeout, failure, explicit stop or channel drop close the invocation's
authority; the scope cannot be re-exported after closure. Broker owner IDs are now
fresh per invocation, rather than a process-global owner.

Next (superseded by the child implementation checkpoint below): wire the paired OCI
processes and no-fallback lifecycle, then execute the actual UID/namespace,
worker-impersonation, target-contact, teardown and restart matrix. The OCI broker still
runs in the supervisor until that wiring is complete. S12 remains Partial,
uncommitted and earns zero weight; overall stays **33.9% / 66.1%**.


S12 private child implementation checkpoint (not stage acceptance): the helper
now runs the policy engine over owned stdio without loading operator configuration
or constructing a general Control client. Two actual native-process tests pass;
the mTLS send-gate regression passes all six direct/helper scenarios (403, false
200, positive 200). All-target Clippy passes. Testing found and fixed a lost
failure-watch notification when there were no subscribers, and shutdown now
drops the poisoned channel's pipes. These native processes do not establish OS
identity or namespace separation. A development scratch image containing the
statically linked broker was built locally, with no workload identity or shell;
it is not a final source-bound qualification artifact.

The paired OCI lifecycle is now being wired: a required immutable broker image,
disjoint subordinate identities, durable pair recovery and broker readiness before
worker start. Its tests and actual OS qualification are pending. No S12 weight
or new containment claim is earned; progress remains **33.9% complete / 66.1% pending**.


Paired-lifecycle focused verification: **56 library, 13 dispatch, two native
broker-process tests and the six-scenario mTLS gate test passed**, with the
separate OCI cases explicitly ignored by those ordinary commands; all-target
Clippy passed. A locked musl build and local rootless scratch-image build now
complete under an owned source-digest guard. Pair qualification checks each image
pin, subordinate-ID capacity for both roles, and trusted live probes for distinct
UID/GID maps and user/network namespaces. The forthcoming delegated-service run
must establish that these checks work on actual OCI processes and that pair
teardown/restart succeeds; these checks are not yet accepted evidence.


The first actual paired OCI attempt failed closed during namespace comparison
before reopening the pool (no stage acceptance). Qualification is being corrected
to hold both trusted probes alive during comparison, preventing reuse of a
namespace identity after a sequential probe exits. The isolation requirements are
unchanged; the corrected source/image and delegated-service rerun remain pending.


The live paired namespace probe and actual restart recovery now pass. The next
OCI fixture was correctly refused because its profile still declared a 4 MiB
response ceiling, despite its SDK descriptor declaring 2 MiB. The fixture profile
is being aligned with the explicitly bounded separated tier; no production
ceiling is silently truncated. Broker-crash and worker private-channel-forgery
cases are added to the required OCI runner and await execution. S12 stays Partial.


Seven actual OCI cases now pass, including approved SDK target contact and lease
revocation, paired crash cleanup and zero-Control-contact worker impersonation
refusal. One older direct cancellation fixture had no signed-admission metadata
and was refused by the new mandatory broker readiness path before target contact;
its containment-only dummy admission is now explicit (not cryptographic evidence).
The full eight-case matrix must pass together. Request/response header budgets are
also being enforced on the separated transport path before final stage checks.


All **eight actual OCI cases passed together** in the development-image matrix:
paired recovery, admitted SDK target contact/lease revocation, broker crash,
cancellation/deadline, concurrent private workers, output flooding, ambient
authority refusal and worker private-channel impersonation with zero Control
contact. The final runner additionally requires a builder report matching the
current source, parent revision, static binary and immutable broker image; the
final frozen-source rebuild/rerun is pending. The broader Rust workspace passed,
and subsequent response-header changes passed focused regressions and Clippy.
S12 remains Partial until final stage-level verification is complete.


Final pre-qualification checks: **58 Tool Host library tests, two actual native
broker-process tests, 13 dispatch tests and the six-scenario mTLS send-gate test
pass**; two separate OCI cases are ignored in those ordinary commands, not
credited by them. Workspace all-target Clippy passes. CI checks pass **380 tests
with 17 explicit environment skips**; all **20 foundation contract tests pass**.
The final OCI gate requires every named case exactly once and a current-source
broker image report. Its final image build and eight-case rerun are next.


### S12 complete — isolated invocation brokers (2026-11-02)

The complete S12 stage is delivered for the declared Linux x86_64 Python OCI tier.
A required independently pinned scratch image runs the trusted policy engine as
a single-invocation process in a disjoint subordinate UID/GID range and private
user/network namespaces. Worker stdio cannot select or reach its private
supervisor authority channel. The supervisor retains the general Control client
and workload TLS key. Narrow fixed-scope authority calls and immutable buffered
transport preserve the exact request fingerprint, positive OLO send gate and
single-use handler ownership; there is no reconnect/resend or in-process fallback.

Readiness follows two simultaneously live trusted isolation probes. The durable
journal owns both container names before creation; broker readiness precedes
worker start. Broker/channel loss, normal results, output floods, cancellation,
deadline and lease revocation close authority and remove both containers.
Absence of both must be confirmed before reusing the subordinate identity slot;
uncertain cleanup retains the record and fences the pool. Legacy worker-only
journals are cleanup records, not retroactive broker qualification.

Executed evidence:

- Final current-source broker build and delegated rootless gate passed all
  **eight required named actual OCI cases exactly once**, with unchanged source
  throughout. Old source/image provenance was separately rejected before launch.
- Known tested parent: **115f401329777bc44968c59815254fae1da06b83**.
- Tested source SHA-256:
  **e4694ec8de380dc5bbc960b87446f20b2043a8133811f4a9df6264d8f45bf583**.
- Static broker binary SHA-256:
  **eb7db211336b2108e93f767e871a89b66fb6539ac380d20a26f5ceddb366b12a**.
- Broker image:
  **localhost/olo-toolgate-invocation-broker@sha256:1950d46f025485de9c949692df928d2002754575d9b78633cd8f3e89c819f7ea**.
- Worker image remains
  **docker.io/library/python@sha256:aa943ce411423c7582288f18707ff66eddcbaeb510502d747a72aeb34f30d159**.
- Final focused **58 library, two actual native-process, 13 dispatch tests and
  the six-scenario mTLS gate test passed**; ordinary ignored OCI cases earn no
  credit from those commands. Workspace all-target Clippy passed.
- Rust workspace tests passed during the stage; later header/scope/fixture
  changes were covered by affected focused checks. CI **380 tests passed with
  17 explicit environment skips**, and **20 foundation contract tests passed**.
  Python syntax and diff whitespace checks passed. Hosted CI is not claimed.
  No Java, database schema or target implementation changed in S12.

Final acceptance documentation updates follow that frozen tested source; they do
not claim to be the source hash of the commit being created. Operator guidance
and ADR 049 disclose the required brokerImage upgrade, two identity ranges per
slot, 128 MiB broker cap, 2 MiB declared request/response tier, bounded framing/
headers and current-source image preparation. Native and other tiers inherit no
separation guarantee. The broker, supervisor proxy, engine and kernel remain
trusted; full interface conformance, deployment SLOs, generalized effect grants
and complete original roadmap acceptance remain outstanding.

S12 adds its unchanged **1.75** baseline weight. Overall delivery is now
**15.125 / 39.5 = 38.3% complete; 61.7% pending**, with **15 / 33 stages Done**.
Full roadmap phases remain **0 / 7 accepted**. Next: S13's complete declared-tier
containment/egress/interface matrix and explicit refusal of unqualified tiers.


### S13 complete — declared containment/interface matrix (2026-11-02)

S13 is complete for the declared Linux x86_64 Python OCI tier under the existing
baseline. [The matrix](qualified-containment-matrix.md) records the boundary,
required witnesses, disabled features and historical catalog limitations.
The preserved revision-10 plan and the stage weights are unchanged.

The qualified worker stdout boundary now uses strict JSON and canonical offline
schemas before event/authority handling. It refuses invalid UTF-8, duplicate
keys at any depth, malformed/unknown/schema-invalid frames, excessive depth/
bytes/counts, repeated broker request IDs, missing/truncated/duplicate terminal
results and later output. Valid bytewise fragments succeed; malformed output
cannot be repaired by a later success. A result printed during an outstanding
broker call cannot finish the run. Diagnostic-budget failure now drops the
pending authority/target future immediately instead of waiting for the deadline.

The egress matrix found and fixed a real pinning defect: the old verifier trusted
a matching certificate anywhere in an unvalidated supplied chain. Standard
certificate verification plus a verified-leaf pin now precede HTTP delivery.
Unknown issuer, wrong pin/host, expired leaf and copied-CA/forged-chain peers
deliver no application bytes; a trusted valid pinned leaf succeeds. Literal IPs
are checked in the shared transport factory, complete/mixed DNS answers are
rechecked, and redirects never contact same/foreign targets. HTTP/1.1 remains
fresh and non-retrying. Private/self-signed services need an explicit CA bundle
or trusted host roots; intermediate-only pins must be replaced (ADR 051).

The broader workspace gate initially found eight MCP fixture regressions from
bare-pin self-signed trust. They were fixed with a process-local explicit test
CA and independent signed leaf keys, without weakening verification, changing
the OS trust store or writing private keys. All nine real TLS MCP tests now pass,
including changed-key refusal before sending credentials and marked-untrusted
output. Native MCP earns no OCI worker qualification.

Executed final evidence:

- Full Rust workspace tests and workspace all-target Clippy passed after fixes.
- The final source/image-bound delegated rootless gate passed all **12 required
  actual OCI cases exactly once**. Twelve interface attack vectors, socket/
  protocol/metadata probes, five unsupported-profile refusals, sequential state,
  live supervisor-descriptor absence, concurrent identities, lease/cancellation/
  deadline/broker crash and paired restart cleanup executed.
- The final current-source matrix passed **100 native/component tests plus all
  12 OCI witnesses**, covering **eight declared-scope groups**. Raw socket
  witnesses prove pending-call cancellation and diagnostic floods close the
  target connection. A valid-but-stale source report was refused before running.
- CI checks passed **380 tests with 17 explicit environment skips** and all
  **20 foundation contract checks** passed. Python syntax and whitespace checks
  passed. Required commit hooks run at delivery; hosted CI is not claimed.
  No Java/database/target implementation changed in S13.
- Known tested parent: **f7b656005b86c61ba255842a0ac9707896cd1076**.
- Tested source SHA-256:
  **1baf7f3de125c66d7b8d2921e795e6a2560a8d9c4817fc6e6a6642f202f1f009**.
- Static broker binary SHA-256:
  **93f197150c63322a6cd74fc1d7c709f0a0ac399a413df92deaa6320b804dd310**.
- Broker image:
  **localhost/olo-toolgate-invocation-broker@sha256:add257886c326777c2322ab793241f203c981a7c90454eb363e8bc15a2c4e309**.
- Worker image remains
  **docker.io/library/python@sha256:aa943ce411423c7582288f18707ff66eddcbaeb510502d747a72aeb34f30d159**.

The final acceptance documentation follows that frozen tested source; the digest
is not invented as the SHA of this commit. The runner explicitly reports
securityAccepted=false and originalCatalogsAccepted=false. Reusable workers,
TCP/tunnel/Unix-socket transports and other runtime/OS/VM tiers are disabled.
Historical optional-transport, same-host redirect-following, Kubernetes policy,
generalized signed-kill and full release/phase criteria are not silently accepted
as passing. S14 deployment qualification and the later owning stages retain
their requirements. No full roadmap phase or complete C1–C8 guarantee is accepted.

S13 earns its fixed **1.75** baseline weight: **16.875 / 39.5 = 42.7% complete;
57.3% pending**, with **16 / 33 stages Done** and **0 / 7 full phases accepted**.
Next: S14 owned deployment crash/lifecycle, cleanup/restart and measured bounds.
