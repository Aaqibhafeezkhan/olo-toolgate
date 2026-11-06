<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Zero Trust Execution implementation status

The [revision-10 plan](../plans/zero-trust-tool-execution.md) is the preserved
proposal linked by issue #230. A merged design or an authored conformance case
does not establish a passing security claim.

## Overall task progress

Updated: **2026-11-06**. Branch: `codex/zero-trust-target-fencing`.
Previous containment delivery: [`0813b71b`](https://github.com/olo-labs/olo-toolgate/commit/0813b71bbe2a0f9276a1b2e2260b25454a31d3d4).

**Overall: partially implemented.** Scoped deliveries now include:
renewable leases/durable execution recovery, qualified Python OCI containment,
explicit business-key binding, independent operation/permit-attempt journaling, an effect-generation foundation, exact broker transport preparation (explicitly restricted to HTTP/1.1), OLO service HTTP fingerprint grants, independent Control validation of their pinned manifests, actual knowledge-target transport integration, owner-bound OLO single-dispatch gates, a shared SQL target-claim foundation, OLO claim-token consumption/status/outcome APIs, verified target-claim orchestration with memory/RAG transaction integration, source-bound actual Control/Gateway/target outcome qualification, adoption of the complete A1–A9/C1–C8 security model, a fail-closed diagnostic scorecard/CI harness, and reviewed business-key policy with immutable window history and retention maintenance, and proof-bound whole-invocation recovery with immutable predecessor archives and stale-receipt fencing; plus S10 all-attempt finalization, immutable closure/settlement and current-authority caller history under the explicit stage scope amendment below; S12 per-invocation broker identity/network separation with scoped private authority/transport IPC and paired teardown/recovery; and S13 strict worker framing and declared-tier containment/interface/egress qualification with verified-leaf TLS pins; plus S14 managed-unit crash/automatic-restart qualification, engine-uncertainty fencing and measured local teardown/recovery. S18 adds reviewed generic HTTP/native MCP intent, fresh owner grants and guarded sends. S20 delivers explicit current effect keys, independently reviewed bounded target resend, read-only reconciliation and scoped operator readiness, with fresh schemas and current contracts across S01–S20. S21 adds reviewed generic HTTP target fencing, executor-peer binding, target-specific online consumption and shared claim recovery for the two declared target classes. Executed validation for the current
delivery is recorded below. **0 of 7 full roadmap phases have met every exit
criterion.** Phases 0–3 have
partial delivery; phases 4–6 have not started as generalized implementations.
**Weighted delivery progress: 56.0% complete; 44.0% pending.** This planning metric credits only completed delivery stages, not partially completed stages. It is separate from full-phase security acceptance (still 0 of 7). See the baseline below.

The roadmap defines **7 major phases**, not a fixed number of incremental
deliveries. Its original per-phase estimates sum to approximately **35–44
weeks for a sequential phase schedule**; phases 2 and 3 may overlap. These are
the proposal's full-plan estimates, not a measured remaining effort or calendar
completion forecast. The midpoint of each original phase estimate supplies the fixed planning weight below; this is not a measured remaining-effort forecast.

| Plan phase | Status | Delivered | Remaining before full phase completion |
|---|---|---|---|
| 0 — Boundary and harness | Partial | Weighted delivery baseline and one-commit stage workflow; source-bound actual stack gate; adopted A1–A9/C1–C8 threat model across security docs; diagnostic scorecard/CI harness inventories all 220 current security/integration cases (209 original plus 11 S10 component vectors) with explicit missing coverage; preserved revision-10 plan, claim inventory and executable OCI conformance gate with revision/source/image evidence. Hosted 1de646c1 foundation aggregation and scan gates pass; final image release is blocked by the APT vulnerability repair below. | Execute the full C1–C8 case/target matrix and integrate a strict release scorecard; obtain hosted CI/release evidence. Four delivery-stage checkmarks do not satisfy these full-phase exit criteria. Hosted b235b536 Control/Quickstart checks pass; the independent device base repeats the APT finding and blocks release, with device/secured-device correction tracked below. Main synchronization to 18d4ca7f passes local SDK/CLI/CI/Java/scan verification; native LangChain business outcome remains explicitly unqualified below. The current native LangChain refusal gate passes actual fresh composed verification and 14 in-image agent cases: unqualified OUTCOME_UNKNOWN, no write or retry. Hosted reevaluation and native business finality remain outstanding. S21 records actual current-source 13 OCI/118 native cases and five deployment observations, without accepting full original catalogs. S21 hosted head 9ff44fca passes foundation, Rust OCI/deployment, images/LangChain, scans, coverage and Sonar. CodeQL identifies two negative-test fixtures; the fixture-only repair below retains both refusal assertions and requires hosted reevaluation. |
| 1 — Operations, leases and evidence | Partial | Durable device/fence-bound leases; heartbeat/watchdog; admission spool; result outbox; immutable late executor evidence; current fresh SQLite/PostgreSQL schemas and tests; conservative business-key binding; current operation/attempt records and immutable snapshots, positive-proof reservation replacement and conservative operation summaries; reviewed required/argument keys, verified namespaces, bounded optional dedupe windows, immutable generation history and retention tombstones, tested on SQLite/PostgreSQL and Gateway/human paths; S09 whole-operation recovery with fresh current authority, retained attempts/leases/dispatches and late-receipt fencing. S10 complete all-attempt/effect derivation for the declared qualified OLO path, immutable manifests/settlement/finality, independent scope review, PARTIAL business keys, bounded current-authority history and Gateway/MCP/native/console presentation; qualified component vectors and affected regression/OCI/target gates pass. | Complete original distributed transitions/audit-spool/duplicate-execution suites remain required, including task/input, kill, host-spool lifecycle and crash/redispatch assertions. S10 is Done under the explicit bounded scope amendment; this does not accept the original full suites or phase. Live consent/equivalence/generalized target-guarantee sources retain their S19/S20 requirements. S09 delivers public proof-bound recovery, immutable predecessor archives, original deadlines, qualified released-generation replacement and SQLite/PostgreSQL/OCI evidence; it does not accept the entire phase. |
| 2 — Containment | Partial; declared runtime/interface/deployment slices delivered | Hosted 58bdc367 diagnostic evidence identifies an invalid I/O-device selection (io.max ENODEV) before worker startup; actual-store/whole-device discovery passes local 13 OCI/112 native/component checks and five deployment observations without dropping the I/O gate; hosted 64f4991e passed Rust tests/Clippy but failed its OCI startup probe without granting readiness; bounded startup diagnostics pass local OCI/matrix qualification, while hosted startup remains unqualified. Hosted 3514034b passes worker/broker startup after the device correction but fails supervisor identity collection because the host cgroup root lacks resource-limit files; identity/resource collection is now separated and passes local current-source 13 OCI/114 native/component checks and five deployment observations below; hosted c1de7d5a passes supervisor identity collection but fails closed during probe teardown with engine uncertainty; attached-client cleanup ordering passes local 13 OCI/115 native/component cases and five deployment observations below; hosted 0c35e748 diagnostics expose create timeout before cleanup; fresh FUSE-store qualification passes all 13 actual OCI cases below; hosted confirmation remains required. Qualified worker/broker isolation and strict interface/egress matrix, plus exact managed-unit hard-kill readiness and retained engine-uncertainty journals. Final current-source matrix passes 101 native/component and 13 OCI cases; owned local deployment passes weak-mode/signal refusals and three real supervisor-crash/automatic-restart cycles with actual process/cgroup/namespace witnesses and measured bounds. Hosted 1de646c1 now passes the mandatory OCI/interface/deployment Rust job; final product-image scanning remains blocked independently below. | Full original optional-transport/generalized-kill, Kubernetes/production connectivity and release criteria remain unaccepted in their owning stages/S33. Four bounded delivery stages do not accept those original full-phase exit criteria. Other runtime/OS/VM tiers remain unqualified; local measurements are not production SLOs. Hosted b235b536 Control/Quickstart checks pass; the independent device base repeats the APT finding and blocks release, with device/secured-device correction tracked below. |
| 3 — Effect authorization and recovery | Partial; ledger foundation | Durable immutable effect identities; authorization generations/owners; release/send/consume/ambiguity states; target-vs-broker evidence; timeout and terminal-invocation abandonment. Integrated with existing OLO write permits. Broker-owned prepared HTTP requests now have versioned fingerprints and explicit no-retry HTTP/1.1 transport. OLO service permits sign those digests and require target recomputation before consumption. Leased v2 issuance independently checks signed admission and pinned route/profile manifests. The actual memory/RAG listener integration supplies accepted HTTP facts using trusted profile configuration and refuses unbound downgrade. Bound OLO writes require a recorded process/handler owner and a single authenticated AUTHORIZED-to-SENDING transition before dispatch. A target-owned JDBC claim foundation now supports unique ownership, apply/recovery CAS and atomic business completion; the actual memory/RAG adapters now integrate it. Bound write claims now sign effect identity/generation; dedicated service APIs return consumption status and retain authenticated late target outcomes. The target SDK separates verified facts from online consumption, persists APPLYING before external actions, and atomically journals completion/outcome delivery with business SQL. The owned actual Control/Gateway fixture now verifies committed memory/RAG outcome settlement against Control records.  S18 now delivers independently reconstructed reviewed generic HTTP/native MCP intents, immutable SQL intent records, fresh two-second unfenced grants and one-owner monotonic send transport. Native MCP approval stamps are Control-owned; legacy unbound writes are refused at issuance and historical consumption, current read permits also require prepared HTTP facts; older incomplete formats are refused. S19 delivers mandatory high-risk exact ASK review, immutable bounded resolved previews, independent scoped approval and private preview access, pending admission, atomic SQLite/PostgreSQL slot reservation, durable typed refusal audits and signed native consent references. Source-matched 48 focused enterprise and 14 native package cases pass without skips; complete repository gates and affected final checks, current-source OCI 13/matrix 106 and owned memory/RAG qualification pass. Hosted CI on 2e14d34d subsequently found restore-fixture and IPv4/IPv6 TLS-fixture failures; the complete post-delivery repair passes local verification below; hosted results must follow the new PR head. Application-signed frontend intents remain unsupported. S20 delivers current explicit generic/OLO keys and fresh schemas across S01–S20; independently reviewed pinned target keys, complete-history bounded resend, read-only two-phase lookup with immutable terminal witnesses and scoped readiness. Source/published Java, SQLite/PostgreSQL, TLS/credential rotation, UI, 13 OCI/111 native component cases and actual memory/RAG outcome qualification pass. Replay never renews authority or its original window; ambiguous resend requires every current independent guarantee/history/executor condition. | Kill/dispatch/SLO and complete phase-3 conformance (S22). Native MCP/actual-Control joined qualification, complete partition/crash catalogs, other runtime tiers and production SLOs remain outside this scoped delivery.  S21 delivers the two declared generic HTTP target classes under ADR 057: trusted accepted executor/request binding, target-specific first-consumption ordering and shared SQL ownership/recovery. Executed source-bound checks follow below; this does not accept the complete phase or arbitrary targets. S21 is delivered for reviewed generic HTTPS HTTP/1.1 non-streaming targets using SHARED_SQL or SQL_WITH_EXTERNAL_ACTION, with authenticated consumption and current-schema recovery; no backward support or migrations. |
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
| Exact consent rejects recipient changes and additional effects | Partial; S19 mapped ASK path delivered | Immutable resolved generic HTTP/native MCP previews bind the original argument preimage, operation/run, pinned profile and mapped business request. Current independent scoped approval, expiry/revocation and atomic one-use route slots refuse changed requests and additional effects; requester/agent-owner self-review and metadata-only payload reads are refused. Final source-matched 48 enterprise and 14 native package cases, UI 249, CI 383, complete repository gates plus affected final checks, OCI 13/matrix 106 and the current owned stack pass. Dynamic/unmapped plans, generalized effect keys/resend, other runtime tiers and the complete original distributed catalog remain outstanding; the full vertical-slice guarantee is not accepted. S21 fenced FINANCIAL/DESTRUCTIVE routes retain mandatory exact consent; first consumption calls current authority validation, including consent expiry/revocation and current reviewer privileges. |
| Revocation refuses subsequent sensitive effects and stops healthy workers | Partial | Lease revocation tears down both a real OCI SDK worker and its isolated broker; broker loss and worker private-channel forgery also terminate the pair, with zero Control contact on forgery; S13 raw socket witnesses additionally prove pending-call cancellation and diagnostic failure close the target socket. S14 additionally measures actual managed-unit supervisor-crash/automatic-restart cleanup, including a TERM-ignoring worker. S18 delivers generic HTTP current-authority/owner checks and expired/refused/lost-gate wire witnesses, including slow TLS; the actual Control and scripted-target witnesses are separate, not yet a joined stack qualification. Native MCP has separate actual TLS/owner/send-window witnesses; S18 also passes 13 current-source OCI cases and 106 native/component matrix witnesses. Joined native MCP stack qualification, generalized signed kill and production connectivity-class SLOs remain outstanding.  S21 generic target/peer/accepted-request and actual Control lifecycle/TLS tests, both-class PostgreSQL runtime roles, private broker mTLS/no-retry/send windows and complete stage verification pass. Full distributed/partition catalogs and production SLOs remain unaccepted. S21 first target consumption rechecks current authority, live lease, epoch, deployed profile and human review. Revocation committed first prevents consumption; replay after consumption is status only. Generalized kill/SLO acceptance remains S22. |
| Crashes around dispatch/target commit preserve evidence and prevent unsafe retries | Partial | S20 explicit generic/OLO keys, consent exhaustion, same-generation replay, current-only initialization, independently reviewed history-aware resend, read-only terminal witnesses and scoped readiness pass the bounded stage gates. Fresh SQLite/PostgreSQL stores and current contracts refuse obsolete formats without conversion; target witness closure fences further sends.  Admission/result recovery, database reopen, immutable attempt snapshots and refusal of consumed-attempt replay are tested; S09 focused SQLite/PostgreSQL checks cover atomic rollback, competing public replay, fresh permits, original deadlines and stale receipt fencing; S08 adds two-Control-instance window races, immutable history/retention and refusal to rotate ambiguous work on SQLite/PostgreSQL; TLS tests verify HTTP/1.1-only dispatch and target refusal of changed transport facts before storage; owner races permit one SENDING winner and refused gates contact no target; actual target tests now exercise paused-owner abandonment, two JVMs, writer loss after business commit and lost outcome acknowledgement; complete Control/Gateway crash scenarios remain outstanding.  S21 generic target/peer/accepted-request and actual Control lifecycle/TLS tests, both-class PostgreSQL runtime roles, private broker mTLS/no-retry/send windows and complete stage verification pass. Full distributed/partition catalogs and production SLOs remain unaccepted. S21 shared SQLite/PostgreSQL claims cover both declared classes, real child-JVM halt boundaries and external receipt recovery. APPLYING is never abandoned as not-started, and uncertain provider state cannot reopen dispatch. |
| Partition, duplicates and concurrent target replicas exercise fencing/recovery | Partial | Host Control-partition and OCI recovery tests pass; actual knowledge component storage/TLS/vector qualification is now available; shared SQLite/PostgreSQL claims and actual memory/RAG JVM replicas now exercise one mutation, paused-owner fencing, writer loss and outcome redelivery; actual Control/Gateway normal-path settlement now passes; complete partition/crash qualification remains outstanding.  S21 generic target/peer/accepted-request and actual Control lifecycle/TLS tests, both-class PostgreSQL runtime roles, private broker mTLS/no-retry/send windows and complete stage verification pass. Full distributed/partition catalogs and production SLOs remain unaccepted. S21 both-class SQL ownership, competing replicas, paused owners, apply/recovery races, lost replies, closed provider failure and outbox delivery pass. Complete distributed partition catalogs remain outstanding. |
| Operation finalization includes all attempts/effects and late evidence | Partial; bounded S10 delivered | Control derives the union of every bounded attempt/effect generation, closes immutable manifests and settles only with positive automatic/human scope evidence. Qualified late reports contribute without rewriting current dispatch; conflicts fence authority and final outcomes latch reconciliation. Current-authority caller/history, business-key projections and console presentation pass component regression checks; 143 renewed Control cases (including 17 policy cases and required PostgreSQL scenarios), six actual OCI tests and 32 current target cases pass. S10 is Done only under the recorded scope amendment. Full original distributed suites and later independent consent/equivalence/deduplication sources remain required for full acceptance.  S18 current native forwarding fixtures explicitly use 17 two-independent-reviewer scope attestations and retain REVIEWER_CONFIRMED alongside seven actual target-completed writes; unqualified native scope is never inferred from a successful reply. |
| Revision-bound evidence distinguishes fenced/unfenced guarantees | Partial | S14 final OCI/matrix/deployment reports match known parent ee61e550 and source e1b046f79e5e10ba5b9e5a483bb6c22315efe792af13087bbeeb412c3c37de7b, both image pins, broker binary and measured platform/service properties. They pass 13 OCI plus 101 native/component witnesses and five owned deployment observations. Stale image/source evidence is refused; securityAccepted=false, originalCatalogsAccepted=false and productionSloAccepted=false remain explicit. Full original catalogs and C1–C8 release/phase acceptance remain outstanding.  S18 final source checkpoint 49d1ef7f739bfca3474e4c0aeaab5d30581d1baeef93bc5a4920d688fefdda4b and parent e71bdcaa qualify 13 OCI/106 native matrix cases; runtime-source 1522be7317186f1c5032a4a2b2c3cfa097c8b892507f52a7bb71c8c6fd08e5f4 binds the actual owned stack. Full claim acceptance remains false. S20 source checkpoint 8cb2497bc03bb2e587ce25c70b7666c8925fb2b7842163f574b89d6364a9794c and known parent 8da2b98d qualify 13 OCI/111 native cases; runtime-source 2292274a0cb1515d215daff4267d3a52a418b5274eb6d086aa050707ea7e0620 binds the actual joined stack. Current-stage documentation follows that tested checkpoint; no commit self-SHA is asserted. Hosted run 41931966060 failed on 2e14d34d; those local checkpoint results do not imply a green hosted run. Post-delivery CI repair passes local checks separately; read the new PR head for hosted results. The 64f4991e hosted Python and CodeQL checks pass; its OCI readiness probe failed before a frame, and automatic Sonar retained two SQLite/Oracle findings. These are tracked in the follow-up below. Run 41981447408/job 127100102913 exposes worker OCI io.max ENODEV. Correct storage/device discovery passes current-source local qualification below; hosted reevaluation must follow the repair head. Hosted 3514034b reaches the supervisor identity comparison; the root-cgroup resource-file assumption is corrected below. Hosted c1de7d5a then fails closed during probe teardown; lifecycle/bootstrap repairs and their current-source evidence follow below; hosted 0c35e748 scans pass, while contextual OCI diagnostics now expose a cold create timeout. Hosted 1de646c1 foundation/check, Rust OCI/matrix/deployment, scans and Sonar pass; Quickstart image scanning and dependent verification fail on CVE-2026-109518. The image-base repair is tracked below. Hosted b235b536 Control/Quickstart checks pass; the independent device base repeats the APT finding and blocks release, with device/secured-device correction tracked below. Main synchronization to 18d4ca7f passes local SDK/CLI/CI/Java/scan verification; native LangChain business outcome remains explicitly unqualified below. The current native LangChain refusal gate passes actual fresh composed verification and 14 in-image agent cases: unqualified OUTCOME_UNKNOWN, no write or retry. Hosted reevaluation and native business finality remain outstanding. S21 reports bind parent e1c79b69 and source 12dded0b4397868db0f9988d2f3c19b6737d3d5a9184398c293de2c08c2868b8; 13 OCI/118 native cases and five deployment observations pass. Documentation follows the tested code checkpoint; hosted checks must follow the new PR head. S21 hosted head 9ff44fca passes foundation, Rust OCI/deployment, images/LangChain, scans, coverage and Sonar. CodeQL identifies two negative-test fixtures; the fixture-only repair below retains both refusal assertions and requires hosted reevaluation. |

**Current implementation stage:** S21 delivered; next is S22, revocation, dispatch and phase-3 acceptance.
**Previous repair:** hosted foundation/OCI and all image checks, including LangChain, pass on bbb7213e. PR #278 is merged; S21 delivery evidence for the new branch follows below.
S03, the clarified S04 diagnostic harness, S09 proof-bound rebinding and the explicitly amended S10 finalization stage are complete. Continue in stage order;
dependencies may be implemented inside a stage but cannot earn duplicate credit.
S18 generalized grants, S19 exact mapped ASK consent, S20 qualified resend and S21 declared target fencing are delivered for their bounded scopes. Full distributed operation acceptance and production deployment evidence remain outstanding.

### Standing fresh-build constraint reaffirmed — 2026-11-05

The user reaffirms that this feature has no backward compatibility, database
migrations or legacy runtime support, including future implementation stages.
Use current contracts and fresh initialization only. Future schema changes fold
into fresh schemas; obsolete stores/formats are refused without conversion or
deletion. Migration/compatibility requirements in preserved proposals are
superseded by fresh initialization and refusal verification. This changes no
stage weights or the 39.5-week denominator and earns no delivery credit.

### User-directed S01–S20 fresh-build scope amendment — 2026-11-03

The user explicitly requires a fresh breaking build across every implemented
stage through S20: no backward compatibility, database upgrades or historical
format support. This supersedes earlier compatibility/migration decisions.

Old S20 delivery scope included preservation of legacy default identities,
compatibility constructors, database upgrade handling and migration preflight.
New S20 scope removes those layers across S01–S20, updates first-party callers
and fixtures to current contracts, and initializes one fresh schema for each SQL
dialect. Existing databases/old wire formats must be rejected rather than
converted. Current-operation journals, original deadlines, crash recovery,
independent target review and safe resend remain required current behavior.

Fresh PostgreSQL/SQLite initialization, runtime-role/tenant/storage guards,
current-only contract validation and full affected-stage regression replace
upgrade/compatibility qualification. Target-status adapters, operator eligibility
visibility and the complete S20 stage gates are required for its single
commit/push and requested PR; their executed delivery evidence is recorded below.
The revision-10 proposal remains preserved.

This explicit scope amendment did not itself earn delivery credit. S01–S19 retain
their executed historical deliveries. The complete amended S20 scope has now
passed the delivery gates recorded below. Stage definitions, phase weights and
the 39.5 denominator remain unchanged; the weighted result at S20 delivery was 53.8%
complete / 46.2% pending.
No full roadmap phase is accepted by this amendment.

### Weighted delivery baseline (2026-11-01)

This is a prospective planning model, not a measured fraction of engineering hours
or a security assurance percentage. Phase weights use the original estimate
midpoints: 2 + 7 + 7 + 7 + 3.5 + 6 + 7 = **39.5 planning weeks**.
Each phase's weight is split equally among its explicitly defined delivery stages.
Equal weighting within a phase is a planning assumption, not observed effort.
A stage earns its entire weight only when its stated scope and executed validation
are complete; Partial and Pending earn zero. Existing work is credited only for
the bounded Done stages below, not for the complete parent phase.

**Completed weight = 2 + 7 + 7 + 6.125 = 22.125.**
**Completion = 100 × 22.125 / 39.5 = 56.0% (rounded).**
Pending weight is 17.375, or 44.0% (rounded). Twenty-one of 33 delivery stages are Done;
S10 credit uses the explicit scope amendment below, not full distributed-suite acceptance;
stage count is not the percentage formula. No phase receives full acceptance
until all its original exit criteria have executed evidence.

| Roadmap phase | Planning weight (weeks) | Stages done / total | Earned weight | Weighted contribution |
|---|---:|---:|---:|---:|
| 0 | 2 | 4 / 4 | 2 | 5.06% |
| 1 | 7 | 6 / 6 | 7 | 17.72% |
| 2 | 7 | 4 / 4 | 7 | 17.72% |
| 3 | 7 | 7 / 8 | 6.125 | 15.51% |
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
| S14 | 2 | Containment deployment qualification | Done | Exact delegated managed-unit/SIGKILL readiness; engine uncertainty retains journal and identity slot; source-bound owned deployment passes two weak-unit refusals and three actual supervisor-crash/automatic-restart cycles with measured local bounds. Full workspace, 13 OCI and 101 native/component matrix cases pass. Production SLO/full original-catalog acceptance remains separate. |
| S15 | 3 | Durable effect-generation ledger | Done | Immutable effects, generation/owner transitions, conflict and timeout tests. |
| S16 | 3 | Immutable prepared HTTP transport | Done | Canonical request fingerprints, HTTP/1.1 no-retry transport and TLS refusal tests. |
| S17 | 3 | Fenced OLO memory/RAG integration | Done | Independent manifests, owner dispatch, shared SQL claims, authenticated outcomes, actual JVM crash/replica tests and real Control/Gateway development-stack settlement. |
| S18 | 3 | Generalized effect grants and owner protocol | Done | Reviewed generic HTTP/native MCP intent, durable current-authority grants and single-owner guarded send; legacy write issuance/historical consumption refused, legacy reads retained. Java/source+published-contract builds, Rust workspace/Clippy, UI 245, four-language contracts, Quickstart/browser/restore, PHP/Helm and current-source OCI 13/matrix 106 pass. Current owned stack: 13 gates, 17 explicit independent reviews and seven committed target outcomes. Exact consent, generalized resend/fencing and full phase acceptance remain S19–S22. |
| S19 | 3 | Exact consent effect slots | Done | Mandatory high-risk mapped ASK consent, immutable complete previews, independent scoped review/private payload access, admission gating, atomic bounded slots, signed native references and refusal audits. Full repository gates plus final source-matched 48 enterprise/14 native cases, four-language contracts/UI/CI, current OCI 13/matrix 106 and owned memory/RAG stack (32 component cases, 13 gates, 17 scope reviews, seven settled writes) pass. Application-signed frontend intents, generalized keys/resend and full phase acceptance remain outstanding. |
| S20 | 3 | Per-effect keys, safe resend and reconciliation | Done | Fresh current-only generic/OLO keys and schemas; immutable independently reviewed target bindings, credential/window/history-aware resend, read-only two-phase target lookup and terminal-witness fencing, scoped paginated tenant/operator readiness, UI and operator guide. Both SQL dialects, TLS/custody/rotation, consent/races/late evidence and current contract rejection pass focused and complete affected regressions. Source/published Java checks, four-language contracts, 252 UI cases, standard remaining foundation gates, 13 current-source OCI and 111 native/component cases, 32 memory/RAG component cases and 13 actual joined-stack gates pass. Known parent/source/image evidence and explicit skips/retries are recorded below. No full phase acceptance. Hosted failures on 2e14d34d have a separately tested CI repair below; hosted requalification is tracked per PR head. |
| S21 | 3 | Generalized target fencing SDK/adapters | Done | Reviewed generic HTTP target identity/classes, accepted executor mTLS/request binding, target-specific online consumption/status/outcomes and private broker permit delivery. SQLite/PostgreSQL shared ownership, paused-owner/apply/recovery races, real JVM crashes, retained positive/closed-failure receipts and durable outcome delivery pass. Full repository and affected final Java/CI checks, 13 OCI/118 native cases and five deployment observations pass; source-bound evidence and limitations follow below. |
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
| C1, no ambient target authority | Available for qualified Python OCI pools | Workers have no network socket authority, route, resolver, host mounts or ambient supervisor environment. Native pools do not establish this claim. S19 full effect previews are restricted to the requester and eligible scoped reviewers; operation metadata-read rights alone do not expose their payloads. The source-matched access case passes. S20 broker-only reachable API-key references do not require worker Secret handles; current admission and custody/rotation tests pass. S21 refuses worker-supplied permit headers; only the private broker injects the sensitive signed target permit after immutable request binding. |
| C2, verified sender-constrained run identity | Partial | Device certificate admission exists; sender-constrained agent runs remain planned. S21 binds accepted executor mTLS public-key SHA-256 into generic target grants; this is not S27 agent-run sender constraint acceptance. |
| C3a, current admission authorization | Implemented existing boundary | Signed-permit admission and current Control checks; Tool Host `bad_permits_are_refused_before_anything_runs`. |
| C3b, current per-effect authorization | Partial; declared effect paths delivered | Existing fenced OLO paths independently bind admission, prepared HTTP facts, owners and current authority; actual target claims/outcomes agree with Control in the owned stack. S18 extends current per-effect grants to reviewed generic HTTP and trusted native MCP tools/call: Control reconstructs complete admitted business intent, records immutable intents and owner generations, and rechecks at SENDING. One-use Unix transports enforce a monotonic send window of at most two seconds, preserve TLS/leaf pins and refuse retries, stale owners/profiles and expired/lost gates. Native MCP requires explicit Control-stamped review; unbound OLO writes, including historical claims, are refused. Current-source OCI 13/matrix 106, actual SQLite/PostgreSQL, native TLS JSON/SSE and owned-stack witnesses pass. The native forwarding fixtures require explicit two-independent-reviewer attempt-scope witness and retain REVIEWER_CONFIRMED; they do not establish native containment. S19 exact previews, independent consent/policy reviews, private payload access, durable atomic slots, audit and historical-admission guards are delivered. Final source-matched Control/native checks, full repository gates plus affected final checks, four-language contracts/UI/CI, OCI/matrix and current owned-stack qualification pass. S20 current explicit generic/OLO keys, fresh stores, reviewed pinned target bindings and guarded resend/reconciliation pass the bounded delivery gates. Current scope/authority/credential/history checks and post-I/O lookup rechecks are exercised on both dialects and real TLS; 13 OCI/111 native and current joined-stack evidence pass. This extends the delivered slice without accepting the complete C3 claim. Joined native MCP full-stack/partition/kill catalogs, other tiers and complete claim acceptance remain outstanding. The CI repair makes history pagination arithmetic checked while retaining current public bounds; it adds no execution authority.  S21 delivers the two declared generic HTTP target classes under ADR 057: trusted accepted executor/request binding, target-specific first-consumption ordering and shared SQL ownership/recovery. Executed source-bound checks follow below; this does not accept the complete phase or arbitrary targets. S21 delivers accepted executor/request/target binding, dedicated target-role APIs and current first-consumption checks. Signed declarations alone are not business finality; no complete C3b acceptance. S21 hosted head 9ff44fca passes foundation, Rust OCI/deployment, images/LangChain, scans, coverage and Sonar. CodeQL identifies two negative-test fixtures; the fixture-only repair below retains both refusal assertions and requires hosted reevaluation. |
| C3c, bounded unmediated execution | Partial | Native direct-worker supervision and OCI teardown on cancellation/deadline are tested. Enforced service cgroups contain worker processes; measured deployment revocation SLOs remain required. |
| C4, bound admission intent | Implemented existing boundary | Tool Host admission binds signature, audience, arguments and content digests; dispatch integration tests. |
| C5, contained execution | Available for qualified Linux x86_64 Python OCI pools | Final S14 reports pass 13 actual OCI and 101 native/component witnesses. Readiness checks the actual delegated service, exact cgroup and control-group SIGKILL; uncertain engine completion fences the pool and retains its journal/identity reservation. Five owned deployment observations verify weak-unit refusal and three actual supervisor-crash/automatic-restart cycles with disjoint PIDs/UIDs/namespaces and cleanup without replay. Local observed kill/recovery maxima are 34.797/2697.509 ms at 25 ms polling, not production SLOs. Supervisor/broker/engine/kernel remain trusted. Native/other tiers and full original catalogs/security acceptance remain unqualified. Hosted OCI startup on 64f4991e failed closed before probe evidence; that runner is not qualified by the passing local checkpoint. The follow-up retains bounded startup errors and the same readiness criteria; local current-code OCI 13/matrix 112 pass, without qualifying the failing hosted runner. The current gate fix resolves the rootless store filesystem partition to its whole block device and keeps the same mandatory I/O limits; explicit invalid devices and unknown mappings are refused. Current-source local qualification passes 13 OCI/112 native/component checks and five owned deployment observations below; hosted 3514034b reaches the supervisor comparison but fails on host-root resource files; identity-only supervisor collection passes local qualification below without relaxing worker/broker limits; hosted c1de7d5a passes identity comparison but fences the pool on probe-cleanup engine uncertainty; attached-client lifecycle qualification passes 13 actual OCI/115 native/component cases and five deployment observations below; hosted 0c35e748 exposes create timeout before cleanup; fresh FUSE-store qualification passes all 13 OCI cases below; hosted confirmation remains required. Hosted 1de646c1 passes OCI/matrix/deployment; product image release remains withheld for the separate APT base-package vulnerability below. Hosted b235b536 Control/Quickstart checks pass; the independent device base repeats the APT finding and blocks release, with device/secured-device correction tracked below. Main synchronization to 18d4ca7f passes local SDK/CLI/CI/Java/scan verification; native LangChain business outcome remains explicitly unqualified below. |
| C6, honest tamper-evident record | Partial | Durable admission, immutable executor evidence, independent immutable attempt snapshots, immutable recovery archives and host result retry; standalone SDK SQLite restart/late-completion recovery uses immediate writer transactions. S10 derived-outcome/scope-review records and audit share writer transactions; rollback and replay checks pass. Eleven source-bound component vectors passed without promoting original distributed suites. S19 delivered consent/slot/refusal records use tenant-scoped immutable storage; authenticated typed refusal audits survive rolled-back authority transactions without retaining raw request values. Source-matched rollback/audit cases pass. Hash chains and external anchors remain required. The CI repair gives immutable service request values body-content equality and redacted diagnostics; defensive-copy/content/credential-redaction regressions pass. |
| C7, revocation | Partial | Renewable leases and current authority checks; full target and compromised-host distinctions remain required.  S14 adds source-bound local managed-unit crash/restart measurements; it does not accept production connectivity-class revocation SLOs or an A4 bound. S21 proves revocation-before-first-consumption refusal for generic fenced targets, retaining status/evidence after expiry. Generalized kill delivery and production connectivity SLOs remain S22. |
| C8, operation/effect integrity | Partial | Single-use admission and durable explicit operation keys exist; keyed unknown outcomes cannot reopen via legacy resend. Independent permit attempts and positive-proof reservation replacement are journaled; unqualified or potentially sent consumed work remains fenced. S09 implements immutable proof-bound whole-operation recovery, with default-off admission-time device/pool/image qualification and all predecessor generations released; full-stage verification passes. Effect generations now separate authorization from consumption/outcome on the OLO write path. The HTTP/1.1 broker now binds OLO service fingerprints through signed claims, immutable effect identity and target checks. Pinned OLO request/route validation is independent at Control. A process/handler-owned single-send gate prevents replaying a bound generation's dispatch authority. The SQL target claim foundation tests unique ownership and atomic business completion. Claim-token consumption/status and late target outcomes are exposed at Control, and actual memory/RAG transactions now adopt them with restricted SQL state/binding guards and durable outcome delivery. Reviewed required/argument keys, verified namespaces, proof-bound windows and retention tombstones are implemented and tested across Control, Gateway and human admission. S10 all-attempt derivation, immutable finality, automatic/human scope settlement, late evidence, current-context caller history and console/business-key projections pass the amended stage gates. S19 exact mapped ASK consent slots are delivered. S20 delivered explicit generic/OLO effect keys have no default aliases and cannot bypass distinct-effect consent limits. Lost-reply observations retain the same nonce/generation/deadline and one SENDING winner. Independently reviewed immutable target bindings preserve derived keys across signer rotation; bounded complete-history resend rechecks current authority, credentials and original deadlines. Read-only lookup appends hashed terminal witnesses, preserves ambiguous history and fences future sends. Complete affected SQLite/PostgreSQL, actual TLS/custody/rotation, source/published Java, OCI/matrix and joined memory/RAG outcomes pass; target assumptions remain visible in the outcome grade. Full original distributed conformance remains required, alongside other route classes and independently approved generalized target deduplication/equivalence sources. The current restore fixture now records explicit EXECUTION nonce/operation/attempt bindings rather than omitting the journal; immutable production guards remain enforced. Fresh SQLite namespace identities now require text and byte-empty TENANT identity; binary, Unicode and embedded-NUL boundary cases are exercised. No existing-store conversion is supported. The current native LangChain refusal gate passes actual fresh composed verification and 14 in-image agent cases: unqualified OUTCOME_UNKNOWN, no write or retry. Hosted reevaluation and native business finality remain outstanding. S21 shared ownership, apply/recovery CAS and immutable target outcomes pass SQLite/PostgreSQL and actual JVM crash qualification. Only independently verified positive or closed-failure receipts may settle uncertain external actions; no new owner or business resend is granted. |

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


### S14 complete — managed deployment crash qualification (2026-11-02)

S14 is delivered for the declared local Linux x86_64 rootless Python deployment
profile. The original plan and fixed stage weights are unchanged. Its actual
owned fixture executes the production Sandbox lifecycle without Control
credentials or live effects; it is not a production rollout or complete
Control/Gateway enrollment/kill qualification.

Before advertising OCI readiness, the supervisor now reads the actual systemd
manager and requires the exact configured ControlGroup, delegated controllers,
KillMode=control-group, KillSignal=SIGKILL and SendSIGKILL=yes. The shipped unit
and transient conformance service have immediate group kill. A weak, unreadable
or ambiguous deployment refuses readiness. If an engine command times out or
its I/O outcome is indeterminate, both removal attempts still run but the pool
stays fenced and retains its journal/identity reservation. A later absence query
cannot erase that uncertainty; managed service restart kills old descendant
commands before recovery.

The deployment controller creates only absent generated unit names and checks
its unique description before cleanup. Units stop before their freshly owned
temporary directory is removed; uncertain cleanup retains the directory. It
compiles and runs one explicitly ignored fixture under the unprivileged
image-owning account. The observer independently checks actual main/worker/broker
PIDs, start times, subordinate UIDs, user/net/PID/mount namespaces and cgroup
membership. It verifies the worker ignores TERM, kills the real supervisor,
observes both old roles terminate, and verifies automatic restart under a fresh
systemd invocation reconciles both containers/journals without a new tool run.

Executed final evidence:

- **Five owned deployment observations passed**: separate weak-mode and
  weak-signal refusals, plus **three actual supervisor-crash/automatic-restart
  cycles**. All owned units were stopped and temporary state cleaned.
- Observed local startup times: **3261.053, 1964.840, 2577.736 ms**.
  Pair termination after the controller's kill request:
  **34.797, 30.830, 31.414 ms**.
  Automatic restart/reconciliation from that same point:
  **2606.747, 2682.624, 2697.509 ms**.
  Polling is **25 ms**; local assertion ceilings are **5000/25000 ms**, including
  command latency. These three samples are not a percentile or production SLO.
- Tested platform: **systemd 255 (255.4-1ubuntu8.18)**,
  **6.18.54.1-microsoft-standard-WSL2**, x86_64.
- All **13 named actual OCI cases passed exactly once**, including a real
  completed-but-unacknowledged create that retains its journal and fences
  qualification despite both container names subsequently being absent.
- The current-source eight-group matrix passed **101 native/component tests**
  and all **13 OCI witnesses**. Full Rust workspace tests and workspace
  all-target Clippy passed. CI **380 tests passed with 17 explicit environment
  skips**; all **20 foundation contract tests passed**. Python syntax and
  whitespace checks passed. Stale image/source evidence was refused before any
  deployment unit was created. Hosted CI is not claimed.
- Known tested parent: **ee61e5500bcaae107cd122648515b12e97c4c0f8**.
- Tested source SHA-256:
  **e1b046f79e5e10ba5b9e5a483bb6c22315efe792af13087bbeeb412c3c37de7b**.
- Static broker binary SHA-256:
  **6d10f46f73acc15c4ff4a170d15431cb89f60e05c4bb8db82512d6d8b31906b6**.
- Broker image:
  **localhost/olo-toolgate-invocation-broker@sha256:141de683f7d664e0787ba1da48dafc993d6a0a2c21bf709f2d9a642aeb0c3860**.
- Worker image remains
  **docker.io/library/python@sha256:aa943ce411423c7582288f18707ff66eddcbaeb510502d747a72aeb34f30d159**.

Final acceptance documentation follows that frozen tested source; the digest is
not the invented SHA of this commit. Reports explicitly retain
securityAccepted=false, originalCatalogsAccepted=false and
productionSloAccepted=false. Full original optional-transport/Kubernetes/
generalized signed-kill, production connectivity-class and release evidence
remain in their owning stages/S33. No A4, other-tier or full C1–C8/roadmap-phase
guarantee is accepted. No Java/database/target implementation changed in S14.

S14 earns **1.75** fixed baseline weight: **18.625 / 39.5 = 47.2% complete;
52.8% pending**, with **17 / 33 stages Done** and **0 / 7 full phases accepted**.
Next: **S18 generalized effect grants and owner protocol**; S15–S17 are already
delivered and retain their recorded scope and evidence.


### S18 in progress — generalized effect authorization

S14 was committed/pushed as c60ff56a. S15–S17 are already delivered. S18 starts
under ADR 053: operator-reviewed immutable intent, independent current-authority
grant/intent transaction, one-owner SENDING gate and a monotonic two-second
unfenced send bound. Scope includes ordinary HTTP, native MCP business calls and
legacy unbound service writes. Consent-required routes remain closed until S19's
exact slot source exists; S20 resend/equivalence guarantees are not invented.
Canonical contracts precede generated bindings and application changes. All S18
implementation/verification is still pending; no stage weight is added.
Overall remains **47.2% complete / 52.8% pending**, **17 / 33 stages Done** and
**0 / 7 full roadmap phases accepted**.


#### S18 working-tree checkpoint — 2026-11-03

No stage weight, completion claim, commit or push is added by this checkpoint.
The pushed baseline remains **c60ff56a**. Working changes are retained together
for the required one-complete-stage commit.

Implemented in the working tree:

- Canonical reviewed route policies, immutable Control-created review stamps,
  effect-grant models and generated four-language bindings; served OpenAPI.
- Independent bounded intent reconstruction from the signed original argument
  preimage and reviewed constants. Recipient/payload/path/query/headers/content
  type cannot be replaced with a caller-selected self-consistent digest.
  Policy constants/pointers require NFC; argument values remain exact data.
- Actual tenant writer transactions persist an immutable request intent and
  reserve one generation/owner before signing. Active lease, certificate/device,
  current authority and active profile are checked again at SENDING. Duplicate
  issuance, owner changes and repeated sends gain no replay authority.
- At-most-two-second grants and a monotonic supervisor send budget captured
  before issuance. The one-shot private Unix relay checks all fresh addresses
  and guards outbound writes, including DNS/TLS delay; responses to a complete
  timely send can arrive later. Actual generic TLS peers receive no supervisor
  workload certificate. Cancellation aborts the relay and removes its socket.
- Operator route review, explicit unfenced critical-risk acknowledgment, and
  exact-consent-required fail-closed presentation. Missing reviews refuse generic
  execution; no package-supplied reviewer stamp is accepted.
- V8 tenant-scoped immutable intents and capacity accounting on PostgreSQL and
  SQLite, with V1–V7 unchanged. Prospective kill specifications are renamed
  byte-identically to V9.

Executed focused validation:

- **45 Java cases passed, zero skipped:** intent 6, generic grant 9, existing
  service bridge 22, effect ledger 8. Includes the owned PostgreSQL runtime role,
  retained intent and concurrent issuer/sender winners; SQLite uses the same
  owner protocol. This is not the full javaCheck gate.
- **14 generic TLS scenarios passed** in one native integration test across
  direct supervisor and actual private broker subprocess: allowed, refused,
  HTTP-200/false, lost/error gate reply, expired issuance, expired SENDING reply
  and expiry during slow TLS. Negative cases observe zero business requests.
- Three actual relay tests and the updated dispatch suite (**13 passed,
  2 explicitly OCI-only ignored**) passed. Seven canonical v2 schema tests and
  nine focused operator UI tests plus TypeScript checking passed.
- Broader checks are ongoing. An initial 380-case CI run could not copy the new
  untracked schema into its isolated release fixture; it must be rerun after
  staging complete sources. An initial broader Python contract run found a
  mistaken v1 inventory expectation and stale UI OpenAPI generation, now fixed;
  its full rerun is still required. Broader native tests exposed older generic
  fixtures without fresh authority; the dispatch fixtures are updated, and the
  remaining regressions still need full verification.

Outstanding before S18 can be committed/pushed: native MCP tools/call intent
and guarded send integration; refuse legacy unbound service writes with upgrade
coverage; complete operator/backend review coverage; complete schema/binding,
Java/PostgreSQL/RLS, Rust, UI, actual OCI and joined owned-stack validation.
Exact consent slots and generalized target fencing/resend stay in S19–S21.
Overall remains **47.2% complete / 52.8% pending**, 17/33 stages Done, and 0/7
full phases accepted.


S18 native follow-up: the complete Tool Host native regression run now passes
**117 tests, zero failures, 14 explicitly delegated OCI/deployment-only ignored**;
Clippy for all Tool Host targets passes with warnings denied. This includes
the stronger 14-scenario generic witness where Control owns a usable client key
and a target requests an optional client certificate, yet receives none.
The six real-process strict protocol tests pass after fixing quadratic
unfinished-line scanning; the existing five-second flood bound is retained.
Four direct SQLite intent/storage tests pass, including fresh-generation
binding, immutability, capacity and rollback.

Native checkpoint parent: c60ff56a5a5a362c6d9daa7d941f961d95f109de.
Native source digest: ddd8def013553408a434b89c5ced08ac8e7babc18e6643d27a9f5ba07d5e1c6d.
The manifest in /tmp/toolgate-s18-native-source.json hashes sorted tracked
paths/content under Tool Host, CLI/Python SDK, contract schemas/Rust bindings,
tenant-identity Rust, contract fixtures and workspace Cargo files. This binds
native observations only; no full-stage, OCI or joined-stack acceptance follows.

The earlier broad Java qualification was **not green** (superseded for the focused cases below). Version-count expectations
were updated for V8, but the stale broad run reported their earlier values and
a recovery Flyway failure still requires a fresh result. Overlapping composite
Gradle checks also corrupted generated binary test reports (EOFException);
the overlapping runs were stopped and only their generated test-result binary
directories were cleared. The fresh focused migration/recovery/intent run is
serialized; neither interrupted nor failed runs count as successful validation.


S18 final verification checkpoint for this working increment:

- Fresh serialized Java selection: **70 passed, zero failures/errors/skips**
  (Endpoint 20, PolicyBundle 3, PostgreSQL upgrade 1, SQLite 7, InitialSchema 1,
  recovery integration 22, generic grants 9, intent validation 7). This resolves
  the migration-version and recovery-Flyway failures for those cases; a complete
  fresh javaCheck and stage-level qualification are still required.
- Full operator UI: **243 passed** with coverage and one worker, retaining the
  original test timeout. Earlier resource-contended runs failed a preexisting
  large MCP review timeout; the final complete rerun passed.
- CI Python suite: **380 cases, 17 explicitly skipped, no failures**.
  Contract Python suite: **169 cases, 2 PostgreSQL-only skipped, no failures**.
  Python SDK: **76 passed**. The composite Python command then stopped because
  native PHP was absent; the PHP checks were executed separately in the cached
  network-disabled PHP image identified below.
- PHP binding round trips: **298 v1 and 261 v2 models passed**, including
  security/constant negatives. The new generic manifest exposed a generator bug
  that omitted required JSON-null properties. Generation now preserves required
  mixed/null values while omitting absent optional values. Fixtures cover a null
  auth declaration and an explicit empty-body intent. PHP image ID:
  sha256:a76ea7d3dd8f65eefff85e9c883f4d146808e0075c30ae6bccbb2a3753d373b8.
- Fresh Java contract tests were forced to execute, rather than accepting a
  cached result; they passed. TypeScript binding check/build and both contract
  tests passed. Rust contract tests passed. The native source checkpoint above
  predates the additional null-body fixture/PHP-generator fix; it remains a
  scoped historical native observation, not a new full-stage source acceptance.
- All known task-owned validation runs have completed. No stage commit or push
  was made; this incomplete S18 increment is retained in the working tree.

Next implementation remains native MCP effect intent/guarded send and legacy
unbound service-write migration, followed by the complete applicable stage
gates (including owned OCI and joined stack evidence). The fixed baseline stays
**47.2% complete / 52.8% pending**; no partial-stage weight is earned.


#### User-requested delivery checkpoint — 2026-11-03

The user explicitly requested “commit and move to next” after the incomplete
scope and executed validation above were reported. This instruction overrides
the no-intermediate-stage-commit workflow for this HTTP increment only. Commit
the code, tests, changelog, operator guide and this progress record together;
push to feature/zero-trust-execution-plan before beginning the next increment.
Do not rewrite existing history or label this checkpoint a completed S18 stage.

The weighted baseline and denominator are unchanged: **47.2% complete, 52.8%
pending**. S18 remains Partial and earns zero weight. The next outstanding item
is native MCP tools/call intent and owner/send-window integration, followed by
legacy unbound-write migration and complete applicable stage qualification.
The preserved revision-10 proposal is unchanged. Complete fresh javaCheck,
owned OCI and joined-stack qualification remain unaccepted; focused/component
passes above are not substituted for them.


#### Native MCP increment started — 2026-11-03

The user-requested generic HTTP checkpoint was committed and pushed as
e71bdcaa. The next outstanding S18 item is underway under ADR 054: explicit MCP
effect review, independently reconstructed tools/call intent, and fresh
owner-bound guarded transport. Canonical contracts are being extended first.
No native MCP implementation/validation or additional stage weight is claimed.
S18 remains Partial; legacy write migration and full stage qualification follow.


#### Native MCP and legacy migration working-tree evidence — 2026-11-03

Known delivered parent: e71bdcaa; the following changes are uncommitted, so this
does not identify or predict a future commit SHA. The preserved S01–S33 weights
remain unchanged: 47.2% complete, 52.8% pending; S18 earns zero weight.

Native MCP now requires explicit per-tool classification/risk/consent review,
Control-stamped immutable profiles and independently reconstructed tools/call
intent. The trusted native executor shares the one-use monotonic send transport
with generic HTTP. The guard remains alive through JSON/SSE response consumption.
Missing review and consent-required policies fail closed. Existing approvals
must be reapproved. No native OCI containment or target commit guarantee is claimed.

Executed before the legacy migration changes:
- Owned PostgreSQL/SQLite Java selection: 47 tests, no failures or skips
  (McpServers 12, McpEffectValidation 4, EffectGrant 9, ServicePermitBridge 22).
  This includes real approval/deployment, real signed admission and execution
  lease, immutable artifact checks, changed intent refusal, durable owner dispatch
  and duplicate refusal.
- Complete native Tool Host suite: 118 passed, 14 explicit OCI/deployment skips.
  Includes five actual native Session/TLS grant/gate scenarios and the full
  generic/private-child and OLO dispatch regressions.
- Clippy all targets with warnings denied passed before the migration.
- Initial full UI coverage run exposed a slow 65-tool test. Its repeated DOM
  queries were replaced by one collection query; timeout remains unchanged.
  The complete rerun is pending. Earlier focused MCP UI tests passed.

Legacy unbound write issuance and historical grant consumption now refuse with
the typed BOUND_SERVICE_EFFECT_REQUIRED conflict. Migration tests and the full
stage checks are running; no pass is claimed yet for that change. Legacy reads
remain in scope for compatibility validation.

Next outstanding work: finish focused migration checks/fixes, current contracts
and complete Java/UI checks, then applicable owned-stack and OCI stage gates.
These component witnesses are not a joined native MCP/actual Control qualification.


#### S18 stage verification follow-up — 2026-11-03

Native MCP and legacy-write migration now pass the complete javaCheck gate,
including all 330 enterprise cases with no skips/failures and 23 service bridge
cases. The complete console run passes 245 tests. Rust workspace tests and
workspace/all-target Clippy with warnings denied pass. Canonical generation
verifies 1,175 files; PHP passes 298 v1/263 v2 round trips; Python contract tests
169 (two explicit PostgreSQL-fixture skips), Python SDK 76 and CI regression tests
380 (17 explicit skips) pass. These counts describe the executed selections,
not full roadmap or release acceptance.

The first OCI attempt under the user manager failed its required delegated I/O
controller prerequisite. The administrator-launched service then exposed an
outdated cancellation fixture: no effect authority endpoint or immutable
argument binding. Updating the fixture to check grant ownership and SENDING
ordering, without changing its deadline, resolves that failure. The complete
rerun passes 13 actual OCI tests. The current-source containment matrix passes
106 native/component witnesses plus those 13 OCI cases, with
securityAccepted=false and originalCatalogsAccepted=false.

Known tested parent e71bdcaaa2ad117700e3777382f7c350ca077245; OCI/matrix source
a495f6dec5fcaaad8b0c90d06cc683e83af682401c3155f3602f1d53b470867d,
broker binary d0dc809ad4437545134506c65ded82120c2e642b7ef43e7c9e3036a4b324eaad,
broker image sha256:7274f78dc002f1ce5bf1a0000b362460c991b3bc3e70bf9385bb7f0108670e18.
Subsequent changes are the owned-stack content-identity gate, its negative
controls, its image builder and documentation; no runtime product source changed
after this qualification checkpoint.

S18 remains Partial pending its current owned-stack qualification. The image
gate now permits exact runtime-content binding before the stage commit, while
rejecting stale/malformed labels and source links (six focused negative/control
tests pass). The new image is building. The upgraded external component is
D:/git/olo-mono/olo-knowledge; an isolated source snapshot under
.dev/qualification/knowledge-s18 supplies current SDK/PostgreSQL/TLS/vector
qualification without modifying that checkout. No current full-stack result is
claimed yet. The preserved baseline remains 47.2% complete / 52.8% pending.


#### S18 current owned-stack verification — 2026-11-03

The owned actual Control/Gateway/Tool Host/memory/RAG fixture passes all 13 gates:
real dispatch, replay/concurrent duplicate suppression, private-user and
cross-tenant isolation, exact resource refusal, governed ingest/embedding/
retrieval/citations, ACL narrowing, deletion, per-user quotas, live revocation
and actual target/Control outcome agreement. Three memory and four RAG writes
are committed and Control-confirmed, with zero pending outcome deliveries.

The first run correctly retained OUTCOME_UNKNOWN for unqualified HOST_NATIVE
attempt scope after a successful target response. The external fixture now uses
Control's real two-independent-reviewer, revision-bound attempt-scope API for its
fixed owned forwarding packages. It requires a sealed successful attempt and
resolved effects, refuses missing/ambiguous/failed evidence, and only re-reads
the original logical operation after authoritative review. The pass records 17
REVIEWER_CONFIRMED reviews. This is explicit fixture witness evidence, not
automatic native containment or a production finality guarantee.

External source: D:/git/olo-mono/olo-knowledge, known parent
8e3f7a7141119b800cf10248ab2e2298bc8587dc; executed source digest
97258664c4b9426eb2fbf9f3a3c3581cb5042dd10cc22327a27fc839da2680be.
All 32 PostgreSQL/TLS/vector/evaluation component tests and 30 tooling unit cases
pass. A broad unittest discovery initially imported the standalone integration
entrypoint with its Windows-default Gradle path; the corrected unit selection
excludes that command, which had separately passed all 32 integration cases.
The retained executed component report was restored from the verified full-stack
report and its source/SDK pins were revalidated.

Owned ToolGate image runtime-source identity
b8e4df8f6bee9fc70e88d6bf5b1875528c67d5c3d8050a823774c7c0e755b551,
image sha256:37c5ad18dd60b4625cf2f59aecbfc350f89c529a5e56e0d6041e288f277dbfad,
known ToolGate parent e71bdcaaa2ad117700e3777382f7c350ca077245.
The broader tools/check.py run is in progress with bounded local workers,
cached PHP 8.2 tooling and Helm 3.17.3. No success is claimed for that gate yet.
S18 remains Partial and the fixed weighted baseline remains 47.2%/52.8%.


### S18 completed delivery — 2026-11-03

This completes S18's declared generalized HTTP/native MCP grant and owner scope,
together with the earlier user-requested HTTP checkpoint e71bdcaa. Existing
history is preserved. One complete-stage commit per affected repository contains
the remaining implementation, tests, operator documentation and this progress
update. No partial-stage weight or additional denominator change is introduced.

S18 earns 0.875 baseline weight: **19.5 / 39.5 = 49.4% complete; 50.6% pending**,
**18 / 33 stages Done**, **0 / 7 full roadmap phases accepted**.

Final executed evidence:
- Local javaCheck and the repository gate's Java build, published-contract
  JAR/POM/sources/Javadoc builds, required missing-artifact rejection and local
  enterprise rerun pass; enterprise suite is 330 cases with no skips/failures.
- Complete Rust workspace tests and all-target Clippy with warnings denied pass.
  The complete Tool Host selection has 119 native cases; 14 environment-specific
  OCI/deployment cases remain explicitly ignored there and the mandatory 13-case
  OCI gate is executed separately.
- UI 245 cases, canonical generation (1,175 files), Python contracts 171 (two
  explicit PostgreSQL-fixture skips), Python SDK 76, CI 383 (17 explicit skips),
  TypeScript/Rust/Java contracts and PHP 298 v1/263 v2 round trips pass. Every PHP
  source lints. Java examples, UI packaging and all Helm render/schema/negative
  checks pass; the declared ServiceMonitor CRD schema skip remains explicit.
- The initial full tools/check.py invocation stopped at an outdated native-smoke
  expectation. Its completed Java/publication/Rust/UI/example gates were retained;
  the corrected Quickstart smoke and remaining packaging/PHP/Helm functions then
  passed. The original command did not itself exit zero. The Maven-plugin E2E
  subtest was explicitly skipped because Maven is unavailable.
- The corrected owned Quickstart fixture uses real independent revision-bound
  scope review only for its fixed examples; requester, agent owner and original
  reviewer cannot approve their own attempt. Three focused controls pass. Actual
  Java/Python SERVICE/HUMAN parity, exact retry, package deployment/pull-mode,
  ASK, current-authority revocation, browser administration and signed offline
  backup/restore pass. This does not relax the operation-finalization guard.
- Final OCI: 13 passed; containment matrix: 106 native/component witnesses plus
  those 13 OCI cases. A refresh under concurrent release-build load missed a
  startup deadline; the unchanged serialized rerun passes. No deadline was raised.
- Final owned actual Control/Gateway/Tool Host/memory/RAG stack passes 13 gates,
  17 explicit independent scope reviews and seven committed target outcomes
  (memory 3, RAG 4), with zero pending target delivery. All 32 real target component
  cases and 30 tooling unit cases pass. Native scope is REVIEWER_CONFIRMED,
  not automatic containment or production finality.

Known tested ToolGate parent: e71bdcaaa2ad117700e3777382f7c350ca077245.
Final OCI/matrix source checkpoint:
49d1ef7f739bfca3474e4c0aeaab5d30581d1baeef93bc5a4920d688fefdda4b.
Broker binary d0dc809ad4437545134506c65ded82120c2e642b7ef43e7c9e3036a4b324eaad;
broker image sha256:7274f78dc002f1ce5bf1a0000b362460c991b3bc3e70bf9385bb7f0108670e18.
Final owned-image runtime-source identity:
1522be7317186f1c5032a4a2b2c3cfa097c8b892507f52a7bb71c8c6fd08e5f4;
image sha256:fab0c9ba7e20727c6852b88fe89d3d3b5d02bc4a54667df4305b9c399238b6e0.
Only delivery/progress documentation changes follow these tested checkpoints;
no future commit SHA is asserted. The external knowledge source/parent and
scope-review qualification are recorded above and in its own evidence guide.

Next: **S19 exact consent effect slots**. Consent-required effects remain closed;
per-route one-effect identity, unsupported dynamic intent, no generalized resend
and Unix-only guarded transport remain explicit limits. Full original case
catalogs, production SLOs and C1–C8 acceptance remain unaccepted.


External S18 fixture delivery is committed/pushed as
[63de7b94bbf2837aca570b124427cea5639a36ab](https://github.com/olo-labs/olo-mono/commit/63de7b94bbf2837aca570b124427cea5639a36ab)
on olo-mono's feature/zero-trust-execution-plan. Its tested code digest remains
97258664c4b9426eb2fbf9f3a3c3581cb5042dd10cc22327a27fc839da2680be.


### S19 started — 2026-11-03

S18 is committed/pushed as 9ae6ac66ed501d44b66661e09bd53126a180a49c;
olo-mono's fixture delivery is 63de7b94bbf2837aca570b124427cea5639a36ab.
The next stage starts immediately under ADR 055. Its first working-tree change
requires exact consent for FINANCIAL/DESTRUCTIVE even when a legacy policy flag
is false and unfenced risk was acknowledged; native MCP refuses these before
credential redemption or network contact. Focused checks are running. No slot,
approval/UI completion or passing stage qualification is claimed yet.

Overall weighted progress remains 49.4% complete / 50.6% pending, 18/33 delivery
stages and 0/7 full phases accepted. Do not commit this intermediate S19 fragment.
Next: canonical consent records, immutable previews/approval and atomic slot
reservation with effect generation, followed by runtime/UI and stage gates.


#### S19 focused preview increment — 2026-11-03

Known tested parent remains pushed ToolGate 9ae6ac66ed501d44b66661e09bd53126a180a49c.
Working-tree changes now include five canonical consent models with generated
Java/Rust/TypeScript/PHP bindings and structural round-trip inventories. Preview
construction resolves the full reviewed HTTP request, including recipient,
amount, path/query/header values and JSON body. Native MCP previews bind the
fixed target/upstream tool and complete arguments; RPC ownership and opaque
session/protocol context remain independently bound by the execution grant.

The preview snapshot retains exact JCS bytes without NFC-normalizing business
data, binds full arguments/resources/operation/run/profile, caps depth/nodes/bytes,
and cannot extend either the original operation or credential deadline. It is
not an approval and grants no authority. High-risk execution remains closed.

Executed before the persistence increment: 28 focused Java cases passed,
including the new preview/deadline/mutation/preimage checks and existing grant
checks. Native production MCP early refusal check passed across seven refusal
modes. Python contract suite passed 171 tests with two explicit PostgreSQL-fixture
skips; generated model and fixture checks passed. Two test fixture mistakes were
fixed after focused failures: legacy risk classification and a Unicode value
mapped into an ASCII-only header. Unicode JSON-body values are separately tested
without relaxing header validation.

Current increment adds immutable consent/approval/revocation/use records and
atomic writer-transaction reservations with matching current effect authority,
per-identity generation reuse and bounded explicit counts. New active V9
SQLite/PostgreSQL migrations preserve V1–V8; the unimplemented kill specification
moves byte-for-byte to prospective V10. Storage/race/rollback checks are running;
their results are not yet claimed. Authenticated approval, dispatch and effect
boundary integration, UI and full stage gates remain outstanding.

S19 remains **Partial and uncommitted**. Weighted delivery stays **49.4% complete /
50.6% pending**, 18/33 stages Done and 0/7 full phases accepted. No baseline weight
or scope changes. Finish the whole stage, run applicable thorough gates and then
commit code/tests/docs/progress together before pushing.


#### S19 admission/review/effect-boundary increment — 2026-11-03

The working tree now prepares a preview from the original authenticated admission
before dispatch, keeps required-consent operations PENDING_APPROVAL, and separates
exact consent from every existing policy ASK obligation. Human review binds the
immutable digest/revision; a Gateway or stale/changed preview cannot approve it.
Approval preserves original credential/consent deadlines. Current reviewer scope,
revocation, pinned profile and operation/tool/action/resources are rechecked
before dispatch and effect issuance. The internal issuer matches the independently
reconstructed mapped request and reserves its slot atomically with the effect.
Revocation between issuance and SENDING refuses the send.

Signed native admission now carries an optional exact-consent reference. The
native host retains early refusal without that reference; the reference alone
does not authorize a business send, which still requires fresh Control authority.
The execution scope is the authenticated logical operation; caller-declared
agentRunId remains telemetry and does not gain authorization meaning. No broader
cross-operation run identity guarantee is claimed.

New canonical OpenAPI read/approve/revoke routes and the console exact-effect
review panel show mapped values, resource/argument bindings, expiry and explicit
effect counts. Existing policy approval remains a separate action.

Executed: 39 focused Control cases passed, including five durable storage cases
with actual PostgreSQL runtime-role execution, six admission/review/grant cases,
both approval orders and issuance-to-send revocation. Native early-refusal Rust
test passed across seven modes with no Control/upstream contact. Four focused UI
cases passed; that focused command exited nonzero on repository-wide coverage,
so the full suite is running with the unchanged coverage threshold. An attempted
concurrent UI run overlapped Gradle's dependency preparation and lost the vitest
binary; UI and UI-building Gradle checks are now serialized. A readonly fixture
type error was fixed before rerunning.

The pending native qualification increment extends real package registration,
review, signed artifact/admission, certificate-bound consumption and durable
grant/send tests to FINANCIAL and DESTRUCTIVE on SQLite and PostgreSQL. Results
are not yet claimed. Full Java/repository/OCI and affected qualification gates
remain before a single complete-stage commit and push. S19 remains Partial;
49.4% completed / 50.6% pending, unchanged baseline and zero full phase acceptance.


#### S19 thorough verification in progress — 2026-11-03

Current focused evidence: 39 enterprise cases and 14 native MCP package cases
passed without skips, including financial/destructive admission and signed
artifact/permit/grant/send on SQLite and PostgreSQL. The console passed all 249
tests with unchanged global coverage thresholds (87.03% statements, 84.48%
branches, 86.23% functions, 90.58% lines). CI passed 383 tests with 17 existing
explicit environment skips. Tool Host/contracts Rust tests and strict Clippy
passed; required OCI cases are separately qualified below.

The full repository runner's first attempt reached 349 enterprise cases, with
one obsolete V8 migration-history expectation failing. The PostgreSQL and SQLite
history/upgrade/checksum/future-version checks passed after correcting it. The
second full run passed the enterprise stage and found the same old migration
count assumption in EndpointTest, PolicyBundleTest and PostgresTest. Those
fixtures now expect nine initial migrations/eight after preserved V1. Their
affected groups and the complete remaining runner still need a green rerun.
This is not a claim that either full-run command exited zero.

The current-source broker preparation and 13 mandatory OCI cases passed at known
parent 9ae6ac66ed501d44b66661e09bd53126a180a49c and source checkpoint
c476d937615da42361172b84d5296f26c6bdf590c1e1f8ddb7e3be4b0ee47199:
broker binary 7583564a947f25da8dde92e8472ede64d7dae758511055e9944a7f77954c8fb1,
image localhost/olo-toolgate-invocation-broker@sha256:7a5212b1ed2a2837a2a57649de6b4a43ca70d935c16cf5de94e18c0ba7f27b87.
These are retained executed checkpoints, not invented final-commit identities.
Refresh source-bound preparation/qualification after the fixture/progress edits
before claiming current final-stage evidence.

Preview snapshots now leave response-envelope headroom under the console limit:
original arguments remain bounded at 2 MiB; the complete sealed preview is
limited to 1 MiB, with a focused boundary assertion. The review presents body
mode so no-body and JSON null remain distinguishable. New consent tables are
listed in the reviewed schema-ownership inventory with explicit tenant foreign
keys, forced PostgreSQL RLS and immutable INSERT/SELECT runtime privileges.

Eight new canonical consent models and the optional signed admission reference
are generated across all four languages (1191 generated files). S19 is still
Partial/uncommitted; full repository, final image/matrix and affected current
owned-stack qualification remain. Weighted progress stays 49.4% / 50.6%, with
no change to stage weights, baseline denominator or full phase acceptance.

The final preview review added refusal of mapped numeric values outside the
browser-safe range, so displayed recipients/amounts cannot silently round, and
an aggregate route budget before schema decoding. New boundary cases cover
unsafe positive/negative integers, the exact accepted limit and 64 individually
bounded routes exceeding the total preview allowance. Their execution remains
pending the current publication/full verification run. Contract descriptions now
describe implemented exact approval/slot authority rather than the S18 refusal
placeholder. No stage credit is added before these checks execute.

Historical high-risk admissions without a stored exact preview now refuse lease
renewal/reservation as well as effect authority. A new lifecycle case checks
actual heartbeat cancellation for such a pre-preview admission. An ordinary
policy approval cannot silently upgrade that historical operation to exact
consent; existing recovery/uncertainty rules still apply. This additional
migration check also awaits the current recompiling publication/full run.

Consent-required mapped-request mismatches now return the typed
EFFECT_CONSENT_MISMATCH refusal. Authenticated owner-bound effect refusals retain
a digest-only audit event in a separate transaction after authority/slot rollback;
they cannot leave a reservation behind. The new lifecycle case checks the
persisted typed event and a subsequent legitimate grant using the still-free
slot. Its execution is pending the current source recompilation/verification.

The supported consent path is exact ASK-style review, not a bearer-authenticated
self-intent shortcut. Final review removed an unsigned caller-owned approval
shortcut before delivery; requester/agent-owner independence and live scoped
operation.approve authority now match the existing operation-review rules,
including the verified Super Admin exception. A new lifecycle case checks that
a scoped requester still cannot approve itself and an independent reviewer can.
Application-signed front-end intent remains unsupported; ASK-style exact review
satisfies the selected mechanism. This changes no stage weight or denominator.

A final expansion-boundary fix now checks mapped JSON container size before
serialization, including repeated argument references. Path/query/header limits
match the prepared-request protocol (4096/8192/8192), with encoded-query and
aggregate-header bounds applied while building values. Preview schema header
arrays now match that protocol and allow the implicit Accept header alongside
64 declared headers. New focused cases cover exact accepted header/query bounds
and refusal of repeated-body expansion above its allowance; execution is pending
the next focused run. These are enforcement fixes within the existing bounded
intent scope, with no weight or denominator changes.

Full preview payloads now require requester access or current eligible reviewer
authority in addition to ordinary operation metadata reads. A new case proves
that an operation.read-only manager can still read metadata but receives 403
for the full mapped payload, while requester and independent reviewer access
remain available. The requester/agent-owner cases and pre-serialization bounds
will be included in the next source-matched focused qualification.


#### S19 latest source-matched focused checkpoint — 2026-11-03

At known parent 9ae6ac66ed501d44b66661e09bd53126a180a49c and source checkpoint
42a1ee5caf0ff66bf03ab05373abaffac0ee9c35beba3f924336728cd05e5f2b, an isolated
checkout exactly matched the working-tree source before and after qualification.
Its 48 focused enterprise cases and 14 native MCP integration cases all passed
with zero skips/failures/errors. Retained XML and source identity are in the
owned local qualification artifacts. This includes every final reviewer,
privacy, typed-audit and pre-serialization boundary fix described above.

The complete repository runner also exited successfully: source Java
javaCheck/build, publication JAR/POM/sources/Javadoc, published-contract Control
build, expected missing-artifact rejection, source enterprise rerun, full Rust
workspace/Clippy, SDK/example tools, 249 UI cases with coverage, full real
Quickstart/browser/restart/backup/restore smoke, PHP 298/271 round trips and
strict Helm/render/negative gates. It ran before the last affected security
fixes; the source-matched focused run above recompiled and verified those
changes rather than claiming the earlier command tested a later tree.

The fresh four-language contract checks after the final wire-limit alignment
passed (Python 171 with two explicit PostgreSQL-fixture skips, TypeScript and
PHP round trips, generated model/corpus checks). Existing Java SDK Maven-plugin
E2E and ServiceMonitor CRD schema skips remain explicit prerequisites, not
executed results.

The first owned-image preparation correctly refused evidence when source
changed during final security fixes. The subsequent owned current-source image
build completed after the source stabilized. Final broker OCI/matrix and
current owned memory/RAG qualification are still pending before stage delivery.
S19 remains Partial/uncommitted with 49.4% completed / 50.6% pending and zero
full roadmap phases accepted.

#### S19 delivered — 2026-11-03

S19 is Done for the selected exact ASK-style mechanism on independently reviewed
generic HTTP and native MCP mappings. FINANCIAL/DESTRUCTIVE effects always need
consent. Original full arguments and complete resolved business requests are
sealed with operation/run/tool/action/resource/profile/time bindings; browser
unsafe numbers and expansion beyond protocol/preview budgets fail closed.
Current independent scoped reviewers approve the exact digest/revision; requesters
and agent owners cannot self-review except through the existing verified Super
Admin exception. Full preview payload access requires requester or eligible
reviewer authority. Policy ASK and effect consent remain separate gates.

Approval, immutable slots, revocation and distinct-effect use records persist on
SQLite/PostgreSQL. Reservation shares the authority transaction; failed issuance
rolls it back. Authenticated typed refusal audits survive in a separate transaction
without retaining request values. Native admission signs the consent reference;
historical high-risk admissions without a preview cannot renew or obtain effects.

Executed final-stage evidence:

- Known tested parent: 9ae6ac66ed501d44b66661e09bd53126a180a49c. Final affected
  source checkpoint 42a1ee5caf0ff66bf03ab05373abaffac0ee9c35beba3f924336728cd05e5f2b
  passed 48 enterprise and 14 native integration cases, zero failures/skips.
  Java contract round trips subsequently passed. The complete repository runner
  passed before the last affected fixes; their final recompilation/checks are
  identified above. UI 249 with coverage, CI 383 (17 explicit skips), Python
  contracts 171 (two PostgreSQL-fixture skips), TypeScript/PHP and generated corpus
  checks passed. Maven-plugin E2E and ServiceMonitor CRD schema prerequisites
  remain explicit skips, not executed claims.
- Final OCI and containment matrix passed 13 OCI and 106 native/component cases
  at source checkpoint a03d6e670463b0db4d4a736defedbff201e7cad46aee7c4ffb5e91fd9c9cec7a.
  Broker binary e59a6986eb12f7fd5cd4a9300d5326e4a7bbb454e52b52b3cbe95b8f7fc3ebf5;
  pinned broker image
  localhost/olo-toolgate-invocation-broker@sha256:f18930ef7e973763e3bd9272511fa42a70718a46c952ed8a40c54358463f1f27.
  securityAccepted and originalCatalogsAccepted remain false.
- Actual memory/RAG component qualification passed all 32 PostgreSQL/TLS/vector/
  evaluation cases. Current owned stack passed 13 gates, 17 explicit independent
  scope reviews, three memory and four RAG committed target outcomes agreeing
  with Control, with zero pending outcome delivery. Native scope retains
  REVIEWER_CONFIRMED rather than being inferred from a successful reply.
  Runtime source 0c52ff180ad2536b9ab052741a90e2d343da4b0f0209a04c334206a3096e9794;
  owned image sha256:8019dd85379eb6b80d80e613860985bb0c24554f6993a20262390057b77810f9.
  External knowledge revision 63de7b94bbf2837aca570b124427cea5639a36ab,
  source 97258664c4b9426eb2fbf9f3a3c3581cb5042dd10cc22327a27fc839da2680be,
  clean worktree. No external implementation change was needed for S19.

These are executed pre-commit checkpoints, not the identity of this forthcoming
commit. Subsequent edits to this status document do not change the tested runtime.
The progress update ships with the implementation in the single S19 commit.

Limitations: production consent defaults to one effect per reviewed route and the
current stable operation/route effect identity. Generalized effect keys, safe
resend, downstream guarantees and reconciliation adapters belong to S20; target
fencing to S21. Signed frontend intents, dynamic unmapped plans, joined native MCP
stack/crash catalogs, unsupported runtime/OS tiers and production SLOs are not
accepted here. S19 does not complete phase 3 or the first vertical slice.

Weighted progress is now **20.375 / 39.5 = 51.6% complete / 48.4% pending**,
19/33 stages Done, unchanged baseline/denominator and **0/7 full phases accepted**.
Next: S20, per-effect keys, approved safe resend and reconciliation.

### S20 started — 2026-11-03

S19 was pushed in 8da2b98d11bbba224f13b27bee85d2cfde4f9588 before this stage.
ADR 056 records stable per-effect identity, independent target-guarantee review,
first-authorization window accounting, broker-owned downstream keys and read-only
reconciliation. The first increment adds internal identity/window rules and
focused tests; these are not yet wired as runtime resend authority. S20 earns no
partial credit. Weighted progress remains 51.6% complete / 48.4% pending, 19/33
Done and 0/7 full phases accepted. Next: canonical protocol/persistence and
current-authority integration, followed by adapters, migration/operator handling
and complete stage qualification before a single S20 commit.

#### S20 identity/window focused increment

Five focused Java cases passed with zero failures/skips. They exercise scope
separation, bounded ASCII effect keys, unambiguous framed hashing, first-issue
retention accounting, upward-rounded safety margins, original/review expiry and
invalid window refusal. The first command refused a missing prebuilt UI metadata
artifact after browser hooks; a normal dependency build regenerated it and the
focused suite passed. This was build setup, not an ignored test failure.

The canonical optional EffectGrantIssue.effectKey and bounded EffectKey scalar
are now generated for all four languages, including the legacy Java constructor.
Current Rust producers explicitly send no new key until broker/Control wiring is
implemented. Contract verification is pending; no new runtime authority or S20
weight is claimed. Next: persistent identity compatibility and full owner/history
handling, broker/SDK key propagation, independently reviewed guarantees and
reconciliation integration.

#### S20 generic effect-key increment

Generic route calls now propagate a bounded explicit key through the prepared
request and private buffered transport into authenticated Control issuance.
Non-default keys create scoped logical identities. Missing and explicit default
keys retain the exact S18/S19 generic identity; upgrading cannot mint another
effect by renaming the legacy key. This is an explicit compatibility exception
to the new domain-separated keyed hash, with no change to stage weights.

Twenty-seven focused enterprise cases passed without skips/failures (identity/
windows 5, generic grants 10, consent lifecycle 12), including actual PostgreSQL
grant checks. They prove separate generic logical effects and refusal of a second
key against one approved consent slot, with its attempted authorization rolled
back. Java contract round trips, seven focused Python schema checks and two Rust
contract cases passed. Tool Host library tests passed 72, with the existing real
OCI test explicitly ignored in this non-OCI command; the separate focused key/
buffering case also passed. This does not replace the future full S20 gates.

Explicit keyed OLO service calls are currently refused before service authority,
rather than silently using a different key. Native MCP still uses its one default
effect; generic key support does not qualify native containment. No automatic
resend is enabled. Remaining work includes OLO/SDK key propagation, reviewed
target guarantees and broker-owned downstream keys, replay/history/reconciliation
rules and actual adapters, migration/operator handling and complete stage checks.
All S20 changes remain uncommitted until the full stage is complete; weighted
progress remains 51.6% complete / 48.4% pending.

#### S20 lost-authorization-reply increment

A matching still-AUTHORIZED generation can now be observed again by its exact
device/broker/handler/attempt owner after full current-authority and intent checks.
The replay retains the original authNonce, generation and expiry; elapsed time
reduces validForMs instead of renewing the two-second window. SENDING, consumed,
terminal, ambiguous, expired or foreign-owner requests cannot acquire this replay
authority. A positively RELEASED generation may receive a fresh nonce/generation
while retaining its first immutable intent and consent use; this is proven
pre-send replacement, not ambiguous downstream resend.

The recompiled focused suite passed 28 enterprise cases with no skips/failures,
including real PostgreSQL duplicate issuance/send races, plus Java contract
checks. Two concurrent matching issue requests observe one generation; concurrent
SENDING requests have one winner. A clock-advanced replay retains expiry and
the released generation cannot dispatch after replacement. Strict Tool Host
Clippy (all targets, warnings denied) passed. Independent guarantee review,
downstream key injection, OLO/SDK keys and reconciliation remain unimplemented;
S20 stays Partial/uncommitted and receives no weight.

#### S20 OLO service keys and SDK increment — 2026-11-03

OLO service permits now accept the optional canonical effect key. Distinct keys
produce distinct signed effect identities and nonces; target claim consumption
and completion settle those effects independently. Missing, default and the
historical service-write alias retain the exact original OLO capability/effect
identity. No upgrade can acquire a second effect by selecting a legacy alias.
Private broker issuance retains the key alongside its handler and request digest;
changing/removing that key before transport is refused.

The latest focused Control suite passed 53 cases without failures/errors/skips:
identity/windows 6, generic grants 11, consent lifecycle 12 and service bridge 24,
including actual PostgreSQL scenarios. A service-route validation regression
(initially rejecting its historical audience/operation slash) was corrected and
the entire affected group rerun green. The new outcome assertions were also
corrected to use the existing status accessor before that successful run.

Java RouteCall.effectKey and Python RouteCall.effect_key now carry bounded logical
keys as broker metadata, never caller headers. Recorded exchanges can match the
key. Standalone direct-network paths refuse explicit keys instead of silently
ignoring them. The focused Java SDK case and all 28 Python runtime cases pass;
the Java test uses the SDK's supported Java 17 collection API. Fresh Tool Host
library verification passed 73 with one explicit delegated-OCI-only ignore.
Strict all-target Clippy passed. Retained current artifacts are under the ignored
build/security/stages/s20 directory; the known delivered parent is
8da2b98d11bbba224f13b27bee85d2cfde4f9588.

These are focused increment checks, not full S20 qualification. Independent
target-guarantee review, broker-owned downstream key injection, complete
history-aware recovery/resend and reconciliation adapters, upgrade/operator
handling and thorough current-source stage gates remain. No ambiguous resend is
enabled. S20 remains Partial/uncommitted, with **51.6% complete / 48.4% pending**
and no full phase acceptance. Earlier S20 journal entries describe superseded
increments; their temporary OLO-key refusal is replaced by the tested integration
above. Commit/push still waits for the complete S20 stage.

#### S20 target-contract review increment in progress

Canonical target guarantee, route configuration and supervisor-only downstream
key models are generated with legacy Java constructors. Seven focused schema
checks passed. Route declarations reject reserved headers, invalid retention and
unbound credential scopes. Configuration changes containing guarantees now
require independent maker/checker review even for Super Admin/zero-review
installations; runtime qualification checks the exact applied tool record and
live reviewer permissions. These new enforcement changes await focused Java
verification; they do not yet enable resend. The authoritative phase/vertical
slice/C8 entries remain Partial, with all full S20 authority/integration gates
still outstanding and no additional stage weight.

#### S01–S20 fresh-schema checkpoint

The user-directed breaking build now ships one current V1 initial schema per SQL
dialect. Incremental V2–V10 product files and data-copy/backfill upgrade scripts
are removed. SQLite uses a current schema identity/checksum and rejects previous
stage databases, missing identity and incompatible checksums without conversion.

Actual PostgreSQL initialization and runtime-role/tenant guards, current SQLite
initialization/refusal, independent review and public target-deduplicated replay
checks passed. Retained source/XML evidence precedes the remaining constructor/
wire compatibility cleanup; this is not final S20 acceptance. The original
deadline, one target key across new attempts/generations, refusal of extra effects
and unqualified-attempt refusal are exercised. Current-only contract/caller
cleanup, target status adapters, operator visibility and full-stage qualification
remain before the requested single stage commit and PR. Weighted progress stays
51.6% / 48.4%.

Known tested parent 8da2b98d11bbba224f13b27bee85d2cfde4f9588, source ebedee59561148d59b86bb74eb7512ff5771b5d71de086b2ac8df2fb38ca1743.

#### S01–S20 current-only authority checkpoint

Known delivered parent: 8da2b98d11bbba224f13b27bee85d2cfde4f9588.
All changes in this section remain an uncommitted S20 increment. The user
explicitly extends the fresh breaking-build rule to every stage S01–S20;
historical compatibility descriptions in earlier dated entries are superseded.

- Generated Java models expose only current canonical constructors. Current
  callers explicitly supply their complete arguments. Generic and OLO write
  identities require explicit logical keys, with no missing/default/service-write
  aliases. READ owner identity is current behavior, not a write fallback.
- Current OLO issuance requires exact prepared HTTP digest/manifest, signed
  invocation proof and broker/handler ownership. Read claims also bind accepted
  transport facts. Direct target authorization and Control consumption are
  read-only; writes require verified facts plus durable claim-token consumption.
- Generic target initialization has a current schema identity, positive mandatory
  claim expiry and no ALTER/backfill path. Older unidentified stores fail without
  conversion. The knowledge target schema and packaged memory/RAG writes are
  updated to fresh-only initialization and explicit keys; external qualification
  is still pending.
- Native MCP policy and executor tier become required current contracts.
  Upstream read-only/idempotency hints cannot substitute for explicit approved
  effect policy. Current outbox recovery remains, with explicit reported tier;
  incomplete historical reports are rejected rather than converted.

Executed focused evidence before the final native-policy/tier tightening:
43 Control cases (identity/window 6, generic grants 13, OLO bridge 24) and 29
target-library cases pass with zero failures/skips, including real PostgreSQL
claim, RLS and ownership cases. The newer Rust Tool Host library run passes 73,
with one existing ignored case. Seven current v2 schema tests and generation
drift checks pass. These do not accept the complete S20 stage. Current native
MCP/contract regression, external memory/RAG qualification, target lookup,
operator eligibility and complete stage gates remain outstanding. No intermediate
stage commit or push is made; weighted progress stays 51.6% complete / 48.4%
pending, with 19/33 stages and 0/7 original full phases accepted.

#### S20 current-only regression and read-only adapter increment

The current-only recovery rerun passes 36 selected cases without skips, including
the real TLS target-claim transaction and PostgreSQL resend/ownership cases.
The previous complete enterprise regression ran 375 cases and found 16 failures
in old fixture shapes; those affected cases were corrected and the focused rerun
passed. A complete enterprise/stage rerun remains required.

The first HTTPS lookup adapter passes five focused actual-TLS/protocol cases.
Its tested source snapshot was
2deb4598742dc3388dbfdd4a38f76e48de2e9ff469f90670c5d2a9e1c5d644e1.
Later changes tighten explicit READ route review and add current scoped
two-phase lookup wiring, append-only hashed provider receipts, post-network
authority/profile/epoch/generation rechecks, conservative all-generation
projection and resend fencing. Fresh initial-schema guards recognize this
witness without upgrading databases or rewriting old generations. Lookup
lifecycle tests are in progress; no new security guarantee is accepted yet.
Unknown/pending status remains uncertainty, and a resolved effect does not
invent successful completion of an unfinished worker operation. Operator
eligibility/presentation, real PostgreSQL lookup checks, API/UI and complete
stage qualification remain outstanding. S20 stays Partial and uncommitted.

#### S20 current-only admission and operator readiness — 2026-11-05

The current working tree now includes scoped read-only target reconciliation,
append-only lookup witnesses, terminal-history resend fencing, independent
live guarantee review and paginated tenant/operator recovery readiness. Readiness
reports configured eligibility only; current operation-specific history and
authority remain mandatory. The operator guide documents supported lookup
contracts and manual fallbacks. Original AMBIGUOUS generations remain immutable.

Generic reviewed HTTP routes and broker-only API-key references now participate
in qualified Python execution. Reachable auth references are admitted resources
without granting worker-readable secret handles. Other auth modes and raw worker
secret declarations do not qualify. Package upload validates the complete current
v2 manifest in a separate schema namespace; obsolete formats are rejected rather
than converted. Fresh schema initialization and current-format contracts apply
through S01–S20 as directed by the user.

Executed focused evidence so far: 22 lookup/resend/TLS cases with actual
PostgreSQL; 5 preflight/admission cases; 25 UI cases; Java declaration tests and
24 Python declaration tests. The latest Rust Tool Host library run passed 75
tests, failed zero and explicitly ignored one environment-dependent case.
These runs precede some subsequent current-package and qualification edits;
they are not final source-matched stage acceptance. The current full Java and
repository checks are running, and failures will be fixed before the stage
commit. S20 remains Partial with no weight: 51.6% complete / 48.4% pending.

The current increment also restores generated capacity-accounting blocks in the
fresh schemas and teaches the ownership inventory to recognize PostgreSQL's
explicit public schema. All four ownership/capacity checks pass. Current-schema
contract tests replace obsolete backfill/compatibility assertions while retaining
archived fixtures as historical evidence. Full UI validation passes 252 tests
across 36 files; the strict current-policy MCP adapter passes nine tests.

Full current PostgreSQL enterprise regression executed 402 cases with zero
skips: 401 passed and the new API-key custody case initially failed because its
fixture secret name was outside the vault name grammar. The fixture now uses a
valid current name; all ten applicable lookup cases pass in the focused rerun,
with its PostgreSQL wrapper explicitly skipped in that isolated invocation.
Credential confidentiality and rotation during I/O are exercised. Both normally
skipped Python PostgreSQL storage checks also executed successfully in a
separate owned PostgreSQL fixture (five cases across the two modules, no skips).
A complete rerun and refreshed source-bound image/stack evidence remain pending.

The subsequent actual PostgreSQL enterprise run passed all 402 cases with zero
failures, errors or skips. Its raw XML is retained under
build/security/stages/s20/full-postgres-pass-xml. Current-source OCI qualification
passed all 13 cases and the native/component matrix passed 110 cases at source
caf4c961438266b233da8ba5ef78ade600e7da3cfd505eeb24dfd628541e5106,
parent 8da2b98d11bbba224f13b27bee85d2cfde4f9588. The matrix explicitly leaves
securityAccepted and originalCatalogsAccepted false.

The ordinary Control regression then ran 276 cases with nine failures and five
explicit environment/worker skips. The failures identified obsolete test inputs:
seven client-release packages lacked current descriptor fields, one late report
omitted its required current execution tier, and the archive baseline still
expected malformed UTF-8 replacement decoding. Current fixtures now include the
required namespace/execution flags and HOST_NATIVE tier; malformed UTF-8 is
explicitly rejected. All 26 affected package/client/archive/lease cases pass in
focused reruns. Subsequent changes include those fixtures, fresh-build deployment
and repository instructions, and the webhook example's explicit notification
effect key. The example no longer describes a caller reference as a guaranteed
downstream deduplication contract; its existing example gate is pending.
The tested Control/broker runtime remains unchanged. Full repository,
publication and owned memory/RAG qualification remain pending. No S20 stage
weight is earned.


#### S20 complete fresh-build delivery — 2026-11-05

S20 is **Done** under the explicit user-directed fresh-build scope. The current
baseline credits 0.875 additional planning weeks: 21.25/39.5 = **53.8% complete,
46.2% pending**, 20/33 delivery stages. **0/7 original full roadmap phases** are
accepted. S21 generalized target fencing is next; this delivery supplies neither
production revocation SLOs nor full partition/crash/kill catalog acceptance.

Current writes require an explicit bounded effect key. No default/service-write
alias, compatibility constructor, package converter, store upgrade or backfill
remains in the delivered paths. Fresh PostgreSQL/SQLite initialization includes
the complete current schema; old/incompatible stores are refused without deletion
or conversion. Current-schema crash recovery and immutable historical evidence
remain supported. The coordinated olo-mono/olo-knowledge update requires its
current target-schema marker, positive claim expiry and explicit memory/RAG keys.
The webhook example now uses an explicit notification effect key and explains
the limits of its external provider contract.

The actual stack initially exposed a remaining direct-broker placeholder that
refused explicit OLO service keys after dispatch authorization. It was removed:
the existing permit/transport owner binding now carries the same explicit key
end to end. A live Control regression covers both service audiences, exact key
forwarding, substitution refusal and single use. No legacy fallback was added.

Executed delivery evidence (raw logs/reports retained in
build/security/stages/s20 and build/security/knowledge):

- Full source Java check/build and published-contract publication/build proof
  passed. Each complete Control enterprise run passed **402 cases with zero
  skips**; ordinary Control ran **276 cases, five explicit skips and zero
  failures**. The final affected package/archive/lease rerun passed 26 cases.
  All four schema ownership/capacity cases and five separately provisioned
  Python PostgreSQL storage cases passed without skips.
- The full tools/check.py run passed CI/Python/contracts/SDK, source/published
  Java, Rust workspace/strict Clippy, all 19 Java example packages (including
  four webhook cases), and TypeScript generation/build/check before one default
  parallel UI test exceeded its existing five-second deadline. The unchanged
  full UI suite passed **252/252** on a bounded-worker rerun, without widening
  timeouts. The original full command exited nonzero; it is not reported as a
  single green run. All remaining standard gates were then executed explicitly:
  actual Quickstart (13 gates plus browser), UI package, PHP roundtrip/lint and
  strict Helm/Kubernetes render/negative checks passed. Existing prerequisite
  skips remain explicit, including Maven-plugin E2E when mvn is absent and
  separately validated ServiceMonitor custom resources.
- After the final production broker fix, the complete Tool Host test command
  and strict Clippy passed. The library passed **76**, failed zero and retained
  one explicit environment-dependent ignore; OCI-only tests execute separately.
  Actual TLS, API-key custody/rotation, lookup witness limits, resend races,
  consent exhaustion and both-dialect ledger checks passed in the preceding
  focused and complete Java/target runs.
- Refreshed current-source OCI passed **13/13** and the native/component matrix
  passed **111** cases. A first OCI run during competing release compilation
  passed 12 and timed out waiting for the cancellation fixture's approved route.
  The full rerun after compilation passed with unchanged limits. Both reports
  are retained. securityAccepted=false and originalCatalogsAccepted=false remain.
- The coordinated knowledge component qualification passed **32/32** with zero
  skips, including actual PostgreSQL, TLS, vectors and retrieval evaluation.
  The refreshed actual Control/Gateway/Tool Host/memory/RAG stack passed
  **13/13 gates**, joining **three memory and four RAG completed writes** to
  Control generations, with **zero pending outcome deliveries**. Runtime image,
  installed target artifacts, SDK/contracts and component source were verified
  again by tools/security/knowledge.py --verify-only --full-stack. This verifies
  executed evidence; it does not substitute an unexecuted test run.

Known tested parent: 8da2b98d11bbba224f13b27bee85d2cfde4f9588. OCI/matrix source
checkpoint: 8cb2497bc03bb2e587ce25c70b7666c8925fb2b7842163f574b89d6364a9794c.
Broker binary SHA-256:
d04c7ae6bcb2f675a1e11a2bb3f2f5bd25ad48af8ef65512ddb4351d79da7410.
Broker image:
localhost/olo-toolgate-invocation-broker@sha256:0a5687ec618a0f9885c9a41c6060e27e24eff2404583d6b4823b0bb21bb88710.
Worker image:
docker.io/library/python@sha256:aa943ce411423c7582288f18707ff66eddcbaeb510502d747a72aeb34f30d159.
Owned stack runtime-source SHA-256:
2292274a0cb1515d215daff4267d3a52a418b5274eb6d086aa050707ea7e0620;
image ID:
sha256:bf34b3ecc968f2500d9e51a40c87a7456b07aecd0a81c2ae0c21f367eec8029f.
Knowledge component parent: 63de7b94bbf2837aca570b124427cea5639a36ab;
tested code digest:
b6aa87dc556d03357bc4f015b8810b2854ef692221dd942b24ad189dc2ef6067.

Final status/ADR clarification follows those tested checkpoints without further
runtime changes. No SHA for the commit being created is invented. The preserved
revision-10 proposal and RELEASE-NOTES.md remain unchanged. Each affected
repository delivers its complete S20 code/tests/docs/progress together in one
stage commit. Hosted PR checks and complete original release/phase acceptance
remain separate from this local stage evidence.

Final review also removed the unshipped versioned kill-switch upgrade proposal
and its ship-unchanged/next-version test machinery. Nonversioned design fragments
now live in docs/control-plane/schema-specifications; future implementation folds
them into fresh initialization only. The current-schema specification test
assembles empty fixtures, never upgrades an existing application store. Complete
Python contract regression reran **171 cases with two explicit PostgreSQL skips**;
the affected specification/lease selection then ran **five cases on an owned
PostgreSQL fixture with zero skips**. Final metadata/generated-contract preflight
passed (1,215 bindings). These final changes affect design docs/test assembly only;
the executed runtime/image digests above remain unchanged.

Final CI regression passed 383 cases with 17 explicit prerequisite skips; final
metadata/generated-contract preflight and owned-stack evidence verification also
passed after the schema-design cleanup. The complete stage remains one commit
per affected repository.


#### S20 post-delivery hosted CI repair — 2026-11-05

The [hosted run on 2e14d34d](https://github.com/olo-labs/olo-toolgate/actions/runs/41931966060)
failed Python foundation, Rust foundation and macOS Intel packaging. Separate
PR checks reported a CodeQL pagination arithmetic finding and three Sonar
reliability findings. These failures do not establish hosted acceptance.
This repair does not change the stage baseline, delivered weight or full-phase
acceptance; it does not begin S21.

The restore fixture now creates the current logical operation, an explicitly
typed EXECUTION nonce and its original attempt binding before consumption.
Production reservation/recovery guards are unchanged; no compatibility or store
conversion path is added. The actual TLS fixture now uses an IPv4 SAN/target
matching its IPv4 listener, so localhost's host-dependent IPv6 preference cannot
direct it to a different socket. Dispatch deadlines and negative expiry checks
are unchanged.

DMG creation retries at most three identical requests only for hdiutil's
Resource busy failure, with bounded one-/two-second backoff. Other failures and
exhausted retries remain errors with captured diagnostics. Local fixture tests
cover transient success, permanent busy failure and immediate nonbusy refusal;
actual macOS execution requires the hosted job.

History pagination uses checked addition as well as existing limit/cursor
validation. Boundary tests cover minimum/maximum integer limits and the accepted
100-item page. Service request equality/hashCode compare defensive body content;
toString redacts private body, resources and transport/credentials. The new
regression covers equality, changed content, defensive copies and redaction.

The SQL Sonar findings are Oracle PL/SQL rules (empty string equals NULL and
VARCHAR2) applied to SQLite. Oracle analysis now uses Oracle-specific suffixes;
actual PostgreSQL/SQLite engine and schema gates remain in place. No SQL
constraint is weakened or rewritten into another dialect.

Focused restore (10), installer (7) and real TLS tests pass. Full Rust workspace
tests/strict Clippy and the Python foundation group pass. The complete Quickstart
suite, focused PostgreSQL operation-history run and required full Java check
also pass; executed results are recorded below.
Previous image/owned-stack evidence remains bound to its documented S20 source
checkpoint; it is not relabeled as current repair evidence.

Executed local repair verification:

- Required full javaCheck passed: 402 enterprise PostgreSQL cases, no skips;
  ordinary Control 276 cases with five explicit skips; service authorization
  30 cases, no skips. Failures/errors were zero. The earlier focused operation
  selection passed 87 PostgreSQL cases with zero skips.
- Full Python foundation group passed: 174 contract cases (two explicit
  PostgreSQL skips), 78 SDK cases, PHP roundtrip/lint and strict Helm/render/
  negative configuration checks. The seven installer cases were rerun after
  diagnostic-output refinement and passed.
- All 43 Quickstart tests passed, including the ten current-schema restore
  cases. Full Rust workspace tests, formatting and strict Clippy passed,
  including direct/private-child TLS send/expiry checks.
- CI regression passed 383 cases with 17 explicit prerequisite skips.
  Generated-binding/metadata preflight passed (1,215 bindings).
- Actual macOS hdiutil execution, CodeQL and Sonar reevaluation require the
  new hosted run after push. These are not claimed as locally executed/passed.
  Read the checks attached to PR #278 for the repair head; original run
  41931966060 remains failed.

Known tested parent: 2e14d34daeab999e9c36563b24ed54c1492cf032.
Repair source SHA-256:
6b29c04ee5c85113317224fac98202bf81dc3c91ccf8d6076ee968a6d4bbe899.
Hash input is the sorted nine changed non-Markdown paths in this repair,
each path followed by a NUL byte and its complete bytes (Java production/tests,
Rust TLS fixture, Gradle configuration, installer production/tests and restore
fixture). Documentation is excluded. Raw logs and the path inventory remain
under build/security/stages/s20/ci-*. No commit self-SHA is invented.

The code, tests, changelog, verification guide and authoritative progress travel
in the same complete repair commit. Weighted delivery stays 53.8% complete /
46.2% pending, with 20/33 delivery stages and 0/7 full phases accepted.

#### Automatic analyzer and hosted OCI follow-up — 2026-11-05

Hosted run 41976157051 on 64f4991e confirms the Python foundation repair and
CodeQL fix. Rust formatting/tests/Clippy also pass, but the later mandatory
rootless OCI startup probe fails before emitting a frame. The pool does not
become ready; containment/conformance gates remain mandatory.

The previous probe discarded engine stderr. Trusted startup probes now retain
at most 4 KiB of error bytes and expose at most 1,024 sanitized characters,
with worker/broker context and available exit status. The same five-second
frame deadline and complete identity/resource/network validation remain.
Diagnostics add no fallback, retry, grant or qualification bypass. A focused
test covers exit status, error retention and bounded output. The current-source
broker image passed all 13 local OCI cases and 112 native/component matrix cases
before delivery; hosted startup must still be diagnosed from the next run.

Sonar's separate automatic analysis did not apply the Gradle analyzer settings.
The Java array-value finding cleared; the two remaining findings are Oracle
rules on SQLite SQL. The fresh SQLite schema now uses native TEXT declarations
instead of CHAR aliases. Namespace checks explicitly require text and compare
byte length to zero. Byte length preserves embedded-NUL distinctions; binary
identities cannot masquerade as the empty TENANT identity. Fifteen real SQLite
boundary vectors include valid tenant/agent/delegated identities, Unicode,
numeric TEXT affinity, embedded NULs, nulls and blobs, plus immutable archival.
This is a fresh-schema change: older SQLite checksums remain unsupported,
without upgrades, backfills or conversion.

Final full contract rerun passes 175 cases with two explicit PostgreSQL skips;
the Python foundation group and all 43 Quickstart cases pass. Ten Java
SQLite/unsupported-store cases pass without skips using an owned PostgreSQL
fixture for the PostgreSQL rejection case. An earlier isolated invocation
omitted that fixture and could not execute its PostgreSQL case; it was rerun
with the required fixture. Probe diagnostic regression and strict Tool Host
Clippy pass. Source-bound local containment passed 13 OCI and 112 native/component cases.
These do not relabel the failed hosted result as passing.

Backward-format preservation is not a gate requirement. Restore fixtures use
current operation/attempt records; unsupported stores and old formats remain
refused. Actual behavior, isolation and integrity failures must be corrected,
not waived. No stage weights or full-phase acceptance are changed.

The shared Python foundation runner now executes the current Quickstart security
and restore suite for both local full checks and the CI Python group. Its
subprocess gets the scoped app import path without mutating the caller's
environment. The workflow's duplicate explicit step is removed. Gate regression
checks enforce exactly one mandatory shared Python command with no contract-only
bypass, no conditional skip and no continue-on-error. Selection tests exercise
actual runner argument selection and import-path preservation.

Final follow-up validation: unified Python foundation passed 175 contract cases
(two explicit PostgreSQL skips), 78 SDK cases, all 43 Quickstart cases and PHP/
Helm gates. CI regression passed 386 cases with 17 prerequisite skips; the
three new selection tests and six workflow contract tests pass. Java
initialization/unsupported-store selection passed ten cases without skips.
Full Rust workspace/formatting/strict Clippy passed, including bounded probe
diagnostics. No timeout, resource boundary or current integrity guard is relaxed.

Known parent: 64f4991ee827450b637443efb1e11b880ff14bc2.
Local OCI/matrix source checkpoint:
f35179f4ccdfce325bf20e502c0cec51189d4e4c98315782d0aa21ff1c88e2e4.
Broker image:
localhost/olo-toolgate-invocation-broker@sha256:75bed4e1e240850285ea238cae597af581f2de5a4825307184c7bc9eba15bcf3;
binary SHA-256:
b58136fb538a872fccd765304397cd5e9065205d7b7bba5178f9eed19fd5ce3b.
That checkpoint precedes the shared CI-entrypoint/test/documentation changes;
runtime Rust/SQLite code is unchanged after the qualified checkpoint.
securityAccepted=false and originalCatalogsAccepted=false remain explicit.

Follow-up code/gate patch SHA-256: 70b2a29ee9839f57cfb5a6d5025e0528eeb12929ef480de1e228818734154e5e.
Hash input is each sorted changed non-Markdown path, a NUL and its complete
bytes; the path inventory is retained in ci-gate-followup-source.json under the
ignored stage evidence directory. Final documentation follows that tested patch.
No SHA for the commit being created is asserted.

The next hosted run must confirm automatic Sonar SQL results and reveal the OCI
worker/broker startup error. Python foundation, CodeQL and actual macOS Intel
installer/service-start checks already pass on 64f4991e. That is component
evidence for that revision, not a claim that the entire hosted workflow passed.
Weighted progress stays 53.8% complete / 46.2% pending; full-phase acceptance
stays 0/7. Existing history and current-format-only requirements are preserved.

#### Hosted I/O-device discovery correction — 2026-11-05

[The reported failure](https://github.com/olo-labs/olo-toolgate/actions/runs/41981447408/job/127100102913)
on known parent 58bdc36716a56e3cc83d9887d8f9b30ef2fa2951 now includes the
trusted worker-startup error: crun cannot write io.max because the selected
device does not exist for that interface. The runner previously used the service
home filesystem's st_dev directly. Linux's block-cgroup configuration rejects
partition devices with ENODEV; that filesystem ID is not necessarily the whole
device or even the filesystem holding rootless container storage.

This is a current gate/device-discovery defect, not a requirement to preserve
an older runtime or database. Both containment and managed-deployment runners
now inspect the service user's local rootless Podman graphRoot, stat the actual
store and resolve partition metadata to its parent whole block device in sysfs.
They validate the device number and I/O queue. Missing/virtual mappings require
a verified explicit whole-device override; explicit partition IDs, mismatched
topology and remote/rootful engines are refused. No limit, readiness condition,
tier requirement or failure check is skipped. The selected storage path and
device are retained in both reports and startup logs.

Nine focused Linux tests pass, covering whole disks, SATA/NVMe partitions with
non-arithmetic minor mappings, explicit whole-device overrides, explicit
partition rejection, missing/virtual/nonqueued devices, inconsistent topology,
missing partition parents and service-account/rootless storage inspection.
All 395 CI tests pass with 17 prerequisite skips; generated-binding preflight
passes. Actual service-account inspection selects the local Podman store on
8:48. Current-source qualification passes all 13 actual OCI cases, 112
native/component containment cases and five owned deployment observations
(weak-mode/signal refusals plus three supervisor-crash/automatic-restart cycles).
The mandatory limits and existing assertion ceilings remain unchanged.

Known tested parent: 58bdc36716a56e3cc83d9887d8f9b30ef2fa2951.
Tested source SHA-256:
69f385aca5834f838f50129865c81adf3c7ab648ba2a57c10dfc7a759337f71b.
Broker image:
localhost/olo-toolgate-invocation-broker@sha256:75bed4e1e240850285ea238cae597af581f2de5a4825307184c7bc9eba15bcf3;
binary SHA-256:
b58136fb538a872fccd765304397cd5e9065205d7b7bba5178f9eed19fd5ce3b.
Reports retain the actual storage path and whole device. Final documentation
follows that tested source checkpoint. securityAccepted=false,
originalCatalogsAccepted=false and productionSloAccepted=false remain explicit.

The new hosted head must rerun the mandatory OCI/matrix/deployment gates.
These local results do not qualify the previously failing hosted runner.
Weighted progress remains 53.8% complete / 46.2% pending, 20/33 bounded stages
and 0/7 full phases accepted. No new stage weight or full-phase acceptance is
claimed; S21 is not started by this CI repair.


#### Supervisor identity qualification correction — 2026-11-05

Hosted run 41989851816/job 127119905983 on known parent
3514034b58634efdf70a6ca4f3bc0896fa13e92a selects the actual whole device 8:0
and passes worker/broker startup and resource validation. It then fails closed
with "isolation probe file unavailable" when gathering supervisor witnesses.
The shared probe incorrectly reads container resource files at the supervisor's
host cgroup mount root. Linux root cgroups do not expose all non-root limit
interfaces; the supervisor's mount root also need not be its own cgroup.
See the [kernel cgroup-v2 interface documentation](https://docs.kernel.org/admin-guide/cgroup-v2.html).

Supervisor identity collection now reads only trusted proc identity, UID/GID maps
and namespace witnesses. Worker and broker probes still require every memory,
swap, PID, CPU and I/O file, exact limit values and denied network creation.
The exact delegated service/control-group SIGKILL validation is unchanged.
Missing probe files identify the fixed trusted path and OS error for diagnosis;
no limit defaults or optional resource checks are introduced.

Focused tests cover supervisor identities without cgroup files, missing identity
witness refusal and refusal of each missing contained-resource file. Seven
focused cases pass; the actual delegated-service case is explicitly ignored
outside the owned OCI harness and must execute there. Full locked Rust workspace tests and strict all-target Clippy pass.
The rebuilt source-bound broker passes all 13 actual OCI cases, including
restart recovery, 114 native/component containment checks and five owned
deployment observations (two weak-unit refusals and three actual supervisor
crash/automatic-restart cycles). Existing resource limits and assertion ceilings
are unchanged.

Known tested parent: 3514034b58634efdf70a6ca4f3bc0896fa13e92a.
Tested source SHA-256:
29eb14a98cad0e0eafb99cc7676ac874a7495a8ce13213a500db2a6d60cebf47.
Broker image:
localhost/olo-toolgate-invocation-broker@sha256:0ee7ab2604cbaa01bf339c91b2b913c60bcf8cbbdb4d4f3bab6dcdb3fce570d9;
binary SHA-256:
6ededbbdab87b9ac48d1e744bcc145fc348696aa5103e844eae0644330dea84f.
Final documentation follows the tested source checkpoint. Full security,
original catalogs and production SLO acceptance remain false.
The next hosted head must confirm the supervisor correction; these local results
do not qualify the failed hosted revision. Weighted progress stays 53.8% complete / 46.2% pending, 20/33
stages and 0/7 phases accepted. No new stage or compatibility support is added.


#### Probe teardown and CI bootstrap corrections — 2026-11-05

Known parent c1de7d5a0a1565ecadd29c9e054a9c1d15aed236 has two failures in
run 41993064073. Rust job 127130060614 passes worker/broker and supervisor
identity collection but fails closed at probe cleanup with retained engine
uncertainty. Scan job 127130060797 fails before scanning when rustup reports
partial-toolchain recovery and a conflicting Clippy component.

Qualification previously dropped its attached Podman clients before removing
the owned containers. Their kill-on-drop behavior can race container/conmon
teardown. Probe clients and stdin now remain owned through both container
removals, then are explicitly killed/reaped. Early qualification failures also
retain the clients through cleanup. The existing five-second engine limits,
absence proofs, pair-removal attempts and uncertainty journal/identity fencing
remain required. Qualification now preserves the original error alongside any
cleanup failure; engine timeout diagnostics identify the attempted operation.
A process-level regression engine refuses absence unless removal ran while
the attached probe was alive, and verifies reaping and journal cleanup.

The Rust installer now captures and preserves installation diagnostics. Only
the exact partial-install/component-conflict combination permits one uninstall
and reinstall of the same pinned toolchain inside the exact disposable
RUNNER_TEMP/toolgate-rustup directory under GITHUB_ACTIONS=true. Developer,
external, missing and symlinked homes refuse repair. Unrelated errors and a
second conflict fail immediately; no fallback compiler or skipped scan exists.
Normal fresh isolated installation of pinned Rust 1.94.1, rustfmt and Clippy
passes locally; partial-conflict/error/path-boundary cases have focused tests.

Eight focused lifecycle cases and seven installer cases pass. All 398 CI
regression cases complete successfully with 17 explicit prerequisite skips.
Locked full Rust workspace tests and strict workspace/all-target Clippy pass.
Actual source-bound qualification passes 13 OCI cases, 115 native/component
containment checks and all five deployment observations. The shared complete
scan gate passes workflow lint, npm/Python vulnerability audits, license/secret
checks and source scanning with zero blocking targets. Historical archived
findings remain reported under the existing nonblocking archive policy.

A real disposable partial installation reproduces the exact rustup recovery
and Clippy-conflict diagnostic; the new installer uninstalls it once, reinstalls
the same pinned toolchain and executes rustc 1.94.1, rustfmt and Clippy.
An unidentified partial-component conflict without the required recovery
diagnostic is refused instead of retried. No installed developer toolchain was
modified in these fixtures.

Known tested parent: c1de7d5a0a1565ecadd29c9e054a9c1d15aed236.
Tested source SHA-256:
e67c12c3accfa84b98f72dcf58ecca4cfdb551a66c5365af76929bc67bc5a494.
Broker image:
localhost/olo-toolgate-invocation-broker@sha256:64fa5c43e666c0a624451f03c4d3f1ab8faff0dd7afb9756db4638e212b8a46b;
binary SHA-256:
b27bcb060205695712017e3b41169724b610afd4d06fb250167081e8bfd142e3.
Final documentation follows that tested checkpoint. Hosted confirmation must
follow the new head; failed c1de7d5a is not qualified by these local results. Weighted progress
remains 53.8% complete / 46.2% pending, 20/33 bounded stages and 0/7 full phases
accepted. This repair does not start S21 or accept production SLOs/full catalogs.


#### Cold rootless storage correction — 2026-11-05

Hosted run 42012504364 on known parent
0c35e748664d446973dcc460281419e49efa1252 passes scan/bootstrap and Sonar.
Rust job 127195379932 fails closed with the now-preserved primary cause:
"sandbox engine create timeout"; cleanup retains the uncertainty journal.
Thus the earlier attachment-order correction does not establish that cleanup
was the hosted timeout's underlying cause.

[Podman's performance guide](https://github.com/podman-container-tools/podman/blob/main/docs/tutorials/performance.md)
documents native rootless overlay's cold full-image copy/remap penalty for each
new image/UID/GID mapping. Existing local stores had already prepared mappings.
Fresh CI now explicitly selects fuse-overlayfs before the first pinned image
pull, using its dynamic mappings instead of native-overlay cold copies.
An existing storage configuration refuses setup rather than being overwritten
or reset. Runtime code, five-second engine/probe deadlines, resource controllers,
identity separation and readiness/cleanup refusal behavior remain unchanged.
The operator guide specifies the same fresh-store preparation.

A mandatory workflow regression verifies configuration ordering and retained
OCI/matrix/deployment gates. All 399 CI regression cases complete successfully
with 17 explicit prerequisite skips. The complete scan gate passes, including
actionlint, dependency/license/secret checks and zero blocking source targets.

Actual cold-store qualification uses a newly created isolated HOME, graphroot
and runroot with fuse-overlayfs. The pinned worker is pulled and the same current
broker binary is built/pinned in that store before any worker mapping is used.
The current lib/dispatch/sandbox test executables run as the service UID in
exact delegated systemd services with the existing five-second engine and probe
deadlines. All 13 actual OCI cases pass (one restart recovery, two dispatch and
ten containment cases), with zero failures/ignored prerequisites in that
selection. Each executable's source-built binary SHA-256 is retained with its
log; the owned cold-store report records the 13 case names, backend and image.
No warmed native-overlay store is used for this cold-start evidence.

Known tested parent: 0c35e748664d446973dcc460281419e49efa1252.
Tested source SHA-256:
6eca8114a96f171b496fbcfbea04411d53c278dc893c2266f4ad7fd630ed9ddb.
Fresh FUSE-store broker image:
localhost/olo-toolgate-invocation-broker@sha256:a64e110642ffad29614e9ff28759c78b08dcc8b5ce149d6b91e6442ee37c414a;
broker binary SHA-256:
b27bcb060205695712017e3b41169724b610afd4d06fb250167081e8bfd142e3.
Runtime Rust code is unchanged from the preceding full workspace/Clippy,
115-component and five-deployment passing checkpoint. This follow-up verifies
the changed backend through the cold actual OCI suite and complete CI/scan
checks; fresh hosted matrix/deployment results still must follow the new head.
Final documentation follows the tested source checkpoint.
Weighted progress stays 53.8% complete / 46.2% pending, 20/33 bounded stages and
0/7 phases accepted. Hosted confirmation is still required; no production SLO
or full-security acceptance is asserted.


#### Ubuntu image-package vulnerability correction — 2026-11-05

Known parent 1de646c17f10d9617c9ddf8a054ad80140910603 passes hosted foundation
aggregation, Rust/OCI/interface/deployment, scan and Sonar gates. In run
42015419566, Quickstart job 127214465879 fails the mandatory Trivy
HIGH/CRITICAL scan on apt and libapt-pkg6.0t64 2.8.3, CVE-2026-109518.
Dependent images/verify job 127222846815 correctly fails because required
image jobs did not pass. No verification or vulnerability threshold is relaxed.

[Ubuntu's advisory](https://ubuntu.com/security/CVE-2026-109518) fixes Noble in
2.8.4ubuntu0.1. Both Control Dockerfile Ubuntu stages now patch apt/libapt-pkg
before other package installs and reject candidates below that fixed version.
The existing libbrotli correction is retained. Initial APT update/install uses
Acquire::Retries=0, the vendor-recommended bootstrap mitigation until the
patched APT client/library is installed. The final Control base supplies the fix to Quickstart. The independent device
Ubuntu base was missed here; it does not inherit Control packages and is
corrected in the follow-up below. This changes build
image packages, not application contracts or persisted schemas.

Actual Control and Quickstart builds pass. Both final images contain apt and
libapt-pkg6.0t64 2.8.4ubuntu0.1, retaining libbrotli1 1.1.0-2ubuntu0.1.
Pinned Trivy 0.61.1 scans of both actual final images pass with zero HIGH or
CRITICAL vulnerabilities. All 43 in-image restore/security tests pass, as do
the complete fresh SQLite and PostgreSQL runtime gates without browser mode.

The PostgreSQL run exposed a preexisting smoke-gate race: profile/enrollment
readiness can precede the Tool Host's first durable positive check-in sequence.
The gate now waits for that first sequence within the existing profile-readiness
deadline. Format 3 remains mandatory; obsolete formats and malformed/negative
sequences fail immediately. Four focused tests cover delayed success, bounded
timeout, obsolete formats and malformed sequences. The initial PostgreSQL race
is retained in the work history; the complete corrected run passes.
All 403 CI regression cases complete successfully with 17 prerequisite skips.
The complete shared scan gate passes with zero blocking source targets; existing
historical archive findings remain explicitly reported.

Known tested parent: 1de646c17f10d9617c9ddf8a054ad80140910603.
Tested code patch SHA-256: 0a0b712e52a4b85148102894fa4f00952e0648aca916782ca163132b9b85ffcf.
Hash input is each sorted changed code/test path, NUL and complete file bytes
(apps/control-plane/Dockerfile, tools/quickstart/check.py and
tests/ci/test_quickstart_check_in.py). Final documentation follows this code.
Dockerfile SHA-256:
b6169cdd49886faff5d721a80dde6a47f1e25a05b7985e63d8fe5f804f5a9942.
Local tested Control image ID:
sha256:138f67db3a3db0cfd1b772cc75eb18de3ecd4301e5c561b2fd4366beaa49cc41;
Quickstart image ID:
sha256:3c3c5ed61c5c684cdfa0075ac41b0f58a28a059410729152f609a5b64302eb4e.
No SHA for the commit being created is asserted. Hosted downstream image jobs
must subsequently execute; previous skipped jobs are not accepted. Weighted
progress remains 53.8% complete / 46.2% pending, 20/33 bounded stages and 0/7
full phases accepted. No compatibility layer or database migration is added.


#### Independent device Ubuntu-base correction — 2026-11-05

Known parent b235b5368e2d4dc3d72c5115cd1b9a1adc5987ca passes Control and
Quickstart image gates. Device job 127256651563 in run 42025190629 fails the
same mandatory HIGH/CRITICAL scan on apt/libapt-pkg6.0t64 2.8.3,
CVE-2026-109518; dependent image verification correctly fails. The previous
description incorrectly assumed the device inherited Control OS packages.
Its release stage only supplies client artifacts; the actual runtime has an
independent Ubuntu Noble root.

The device now bootstraps patched APT/libapt with zero retries, then enforces
the same 2.8.4ubuntu0.1 minimum before installing supervisor dependencies.
Secured-device builds enforce that floor before network/package operations
on their supplied base. The remaining independent Noble LangChain package
builder receives the same bootstrap fix. CI inventories all four independent
production Noble stages and retains blocking device/secured vulnerability scans.
No base store is converted and no compatibility/migration support is added.

All three focused inventory/gate tests pass. All 406 CI regression cases
complete successfully with 17 explicit prerequisite skips; the complete shared
scan gate passes with zero blocking source targets. Actual device and secured
image builds pass; both contain apt/libapt-pkg6.0t64 2.8.4ubuntu0.1 and pass
pinned Trivy 0.61.1 scans with zero HIGH/CRITICAL vulnerabilities. The actual
LangChain hotfolder package builder also completes with the patched Noble stage.

Real device qualification passes all five enrollment/mTLS/Docker-DNS/recreation/
outage-reconnection gates. Secured-device qualification passes all nine gates:
missing-capability refusal, allowed Gateway enrollment/mTLS, removal of service
capabilities, refusal of firewall changes, IPv4/IPv6/UDP/DNS egress and unsolicited
ingress blocking, recreation, owner power controls and confined reconnection.

Local image extraction uses a newly source-built current Linux GNU client,
packaged by the normal verified archive builder and injected into a matching
Quickstart fixture solely for local device-image assembly. No fixture is
published or committed, and no multi-platform/public bundle acceptance is
asserted by this single-platform fixture. Hosted CI must still use its actual
same-run published-client bundle and execute remaining downstream jobs.

Known tested parent: b235b5368e2d4dc3d72c5115cd1b9a1adc5987ca.
Tested code/test patch SHA-256: 858a7f66eb4a4ec83d680ad77186983ee59b952c64f1341cea6da9f2f32bcb87.
Hash input is each sorted changed code/test path, NUL and its complete bytes:
apps/device/Dockerfile, apps/device/secured.Dockerfile,
examples/langchain/package.Dockerfile and tests/ci/test_ubuntu_image_security.py.
Final documentation follows that tested code. Local device image ID:
sha256:83597be0e398d3a4391687e23fb5b34720bbb10677ddd9daa51deb7525af5711;
secured-device image ID:
sha256:9abf415a96df2dd28a24031ca3acd1c4315b808ff9d5d86dd87af26442c407ca.
The Noble package floor follows [Ubuntu's advisory](https://ubuntu.com/security/CVE-2026-109518);
no self-SHA for the commit being created is asserted. Full hosted downstream completion remains required. Weighted progress
stays 53.8% complete / 46.2% pending, 20/33 bounded stages and 0/7 full phases
accepted.


#### Synchronize current main before LangChain qualification — 2026-11-05

At the user's explicit request, fetch and merge origin/main
18d4ca7ffd649d69443698f4f426b69f47200cb6 into known feature parent
060a4c470ff76a4abe01e23b6d48a7692ea5df69, preserving both histories.
Incoming work includes SDK training/model-validation declarations, tool-schema
generation, annotated Java/JSON examples including HotFolder and transparent
UI assets. The only merge conflict is CHANGELOG.md; both histories' entries
are retained. Existing untracked logo assets matched main byte-for-byte and
were backed up before adopting main's tracked copies.

Main's LangChain history already includes post-restart readiness change
74275152; the fetched range adds no newer direct examples/langchain script
changes. Its incoming HotFolder and SDK sources still must be built and tested
together with this feature. No revision-10 proposal or generated release notes
are changed.

Hosted run 42062934109 on the feature parent passes all image package scans
but LangChain job 127393547027 rejects its first native tool result. A local
reproduction confirms authenticated Control metadata OUTCOME_UNKNOWN/REPORTED,
one unsettled attempt and zero effect records; the Gateway correctly preserves
that conservative outcome. A server-permit 409 in its trace is expected native
dispatch selection, not a reason to relax host placement. No business-success
claim or safety-gate bypass is added by this synchronization.

Combined-source verification passes:
- Full javaCheck with an owned isolated PostgreSQL fixture: 402 enterprise cases
  without skips and 276 ordinary Control cases with five explicit skips; all
  SDK Java modules/checks pass.
- 406 CI cases with 17 prerequisite skips, 83 Python SDK cases, locked CLI
  unit/integration tests, Rust formatting and strict workspace/all-target Clippy.
- Complete shared workflow/dependency/license/secret/source scan gate, with
  zero blocking source targets and unchanged historical reporting policy.

The first direct Java invocation lacked required PostgreSQL environment and
failed fixture setup; it was stopped and replaced by the complete passing
owned-fixture run. No production assertion or prerequisite was weakened.
Known merged-source checkpoint:
52351b21adf780ee493988c7d13f9fa1ac641505670be98261326a2c3e12ecf3.
That snapshot has both known parents above; final documentation follows it.
A second fetch confirms main remains at 18d4ca7f before committing.
No self-SHA for the merge commit is asserted.

Push the merge and inspect the new hosted result before deciding the remaining
native-finality correction; syncing main alone is not claimed to resolve it. Weighted progress remains 53.8% complete /
46.2% pending, 20/33 bounded stages and 0/7 full phases accepted.


#### Current native-outcome release boundary — 2026-11-06

Known parent a0e4f5fd886100547caea69807e5b285b099b780 contains fetched main.
Run 42081275039 LangChain job 127497302794 still rejects its first native
HotFolder list call. S10's explicit scope amendment qualifies OLO memory/RAG
finality, not generalized native execution. The native result has no independent
complete-scope settlement: Control must retain OUTCOME_UNKNOWN/REPORTED rather
than promote worker output to business success. The previous positive native
smoke expectation exceeded that current scope. No finalization policy, schema,
permit, Gateway isError rule or replay/authority check is relaxed.

The example client now surfaces authenticated reserved unknown-outcome metadata
as an explicit UnconfirmedOperation error, exposing only bounded operation
identity and grade/count metadata, never the reported tool payload. It refuses
unknown completion even if an erroneous response omits isError. Positive smoke
and model execution still stop. A separate explicit --verify-native-boundary
mode requires signed-tool discovery and the exact unqualified, non-retryable
OUTCOME_UNKNOWN/REPORTED contract with one unsettled attempt and no effects.
Success, generic transport/permission failure, malformed metadata, missing error
flag or a different grade/count cannot pass. It submits no write, retry or model
call. The image gate invokes this mode explicitly while retaining its real
setup/package/client-contract tests. It does not claim native file completion.

Current-source validation passes:
- Focused parser/workflow tests, including duration-mode refusal after the first
  unknown write, without readback or another send.
- 412 CI cases with 17 explicit prerequisite skips, plus all 35 setup and four
  package fixture cases.
- Shared workflow/dependency/license/secret/source scan gate: zero blocking
  source targets; historical dependency reporting policy is unchanged.
- Fresh current-source Control/Quickstart, source-built Linux client/device and
  actual HotFolder package builds. The owned fresh Compose deployment completes
  package provisioning, enrollment/approval, policy configuration and restart
  readiness. All 14 agent/model-contract tests pass inside its rebuilt image.
  The exact image-gate command passes signed-tool discovery and one read-only
  native call with the required unqualified refusal. No write, retry or model
  call is submitted. Task-owned containers and volumes are removed afterward.

One CI invocation inherited Windows PowerShell through WSL's mixed host PATH
and failed unrelated PowerShell fixtures; the complete rerun with the Linux
toolchain PATH above passes. No product assertion was changed to address that
environment error. Local image assembly uses a verified single-platform Linux
client archive, not the hosted six-platform public release bundle. No paid/live
model call or positive native business-completion qualification is claimed.
Hosted reevaluation of this repair remains required.

Tested code/test/workflow SHA-256: e3bd1781b8d496c4b338467526a22cadd73d11bc869845b38b6a383292833949.
Input is each sorted path, NUL and its full bytes: .github/workflows/images.yml, debug/agent-mimic.py, examples/langchain/agent.py, examples/langchain/test_agent.py, tests/ci/test_agent_mimic.py, tests/ci/test_unified_ci.py, tests/ci/test_workflow_contracts.py.
Documentation follows that tested code; the known parent is recorded above,
without inventing the SHA of the commit being created. Actual local Docker
image identities (Quickstart with Linux assets, device, agent):
sha256:96efe13e0f65a9eecd9234828049ed07ecb03a8882d061189d2f98ca0995c43f;
sha256:29bb46a3b370bad78d1e4ee06fe6d9ff4ca3d8d48b8666708dd2eb6347de51b0;
sha256:ea7e31ecea9f012b59eee5b4dba5cc8945e7611256faca06822ba2f3a6f47da2.

Native positive qualification and independent business closure remain the next
outstanding native work; this does not implement S21 or change the weighted
baseline: 53.8% complete / 46.2% pending, 20/33 bounded stages and 0/7 phases
accepted.


#### S21 generalized target fencing — delivered, 2026-11-06

Branch: codex/zero-trust-target-fencing, based on known tested main revision
e1c79b6945f5e12fc154477b85332118f7751983 (merged PR #278). Its bbb7213e head
passed hosted foundation, clients, LangChain, image verification, Sonar and
CodeQL. That closes the previous repair's hosted uncertainty; new hosted checks
must follow this stage's PR head.

[ADR 057](../adr/057-generalized-target-fencing.md) and the
[operator guide](../operations/generalized-target-fencing.md) define reviewed
generic HTTPS HTTP/1.1 non-streaming targets using SHARED_SQL or
SQL_WITH_EXTERNAL_ACTION. Canonical/generated current contracts bind the exact
target/class and accepted executor mTLS public key. Dedicated target credentials
may consume only their own grants and report their own retained outcomes.
First consumption rechecks exact consent, current permissions, epoch, live
lease, pinned deployed profile and human deployment-review authority. Replay is
status only. The private broker injects the sensitive reserved header after
freezing business bytes; workers cannot supply it or acquire the executor key.

The shared adapter preserves unique SQL ownership, generation-fenced APPLYING
and durable outcome delivery. Expired CLAIMED recovery records an immutable
not-started witness without authorizing execution. APPLYING uncertainty cannot
be abandoned or retried. Independently verified positive business receipts or
closed provider failures may settle the observed APPLYING record by CAS;
confirmApplied/confirmFailed return no owner and send no business request.
The closed-failure fixture retains a provider tombstone that refuses later apply.
Real child JVMs halt after committed claim/apply and external receipt boundaries,
then recovery inspects durable evidence without redispatching the action.
Both declared classes retain claim/outcome tombstones and original deadlines.

Executed validation:
- The full python tools/check.py repository gate passed: canonical/generated
  bindings and four-language round trips, Python SDK/contracts, Java source and
  published-contract assembly (JAR/POM/sources/Javadoc plus absent-publication
  refusal), Rust workspace/strict Clippy, all 19 Java example packages,
  Quickstart/security/restore and browser/runtime gates, PHP, and strict
  install/upgrade/negative Helm checks.
- Final serial Java verification passed 414 enterprise cases with zero skips,
  including actual PostgreSQL runtime roles and both target classes; ordinary
  Control passed 281 cases with five explicit prerequisite skips. The final
  shared SDK rerun passed all 42 cases with PostgreSQL enabled and no skips,
  including real JVM crash recovery and closed external failure.
- Real accepted mTLS/HTTP verification joins actual Control grant/consume/outcome
  logic to shared target SQL and retained evidence delivery. Separate authenticated
  resource tests refuse other-target, general/admin, anonymous and wrong-tenant
  identities. Malformed signatures, peer/request/target changes, deadlines,
  bounded responses, redirects, cancellation and unavailable Control are tested.
- Full UI passed 255 cases; final CI regression passed 413 cases with 17 explicit
  prerequisite skips. The Java SDK plugin end-to-end check was explicitly
  skipped because Maven is unavailable; all 19 example-package checks ran.
- Source/workflow/dependency/license/secret scans passed with zero blocking source
  targets. The current Trivy database was refreshed through host networking after
  a container-DNS download failure; no scanner threshold or assertion changed.
  Historical archived dependency findings remain reported under existing policy.
- Current-source qualification passed all 13 actual OCI cases, 118 native/component
  interface/egress cases and five managed deployment observations, including three
  actual supervisor-crash/automatic-restart cycles. Test commands use unchanged
  enforcement deadlines and preserve engine-uncertainty fencing.
- Java coverage now runs and aggregates the enterprise suite, rather than
  omitting its security tests. The generated root report contains actual
  TargetClaims/TargetFencing and target-client/verifier hits. An executable CI
  regression checks suite selection and preserves nonzero test status. Thresholds
  and exclusions are unchanged. Shared-store SDK tests always execute so a cached
  earlier PostgreSQL skip cannot count as qualification.

One earlier Java invocation failed when overlapping Gradle tasks wrote the same
result directory. The full affected run was repeated serially and passed; the
failure was not treated as a product pass or fixed by changing assertions.

Qualification checkpoint:
- Known parent revision: e1c79b6945f5e12fc154477b85332118f7751983.
- OCI/matrix/deployment source SHA-256:
  12dded0b4397868db0f9988d2f3c19b6737d3d5a9184398c293de2c08c2868b8.
- Changed non-Markdown code/test SHA-256:
  1918c2650a5d6bd3521a1997436c1eec2424d448bca04b63ec8fc3840e7459bc.
  Input is each sorted changed non-Markdown path, NUL and its complete file bytes,
  relative to the known parent (65 files). Final documentation follows that
  tested checkpoint; no SHA for the commit being created is invented.
- Worker image:
  docker.io/library/python@sha256:aa943ce411423c7582288f18707ff66eddcbaeb510502d747a72aeb34f30d159.
- Broker image:
  localhost/olo-toolgate-invocation-broker@sha256:7ad402199dddda60aa9739454692503d4b913b8bf8e13214f752c1794dbbe917.
- Broker binary SHA-256:
  c65d05fa254011aa4f34391d3d1584bb14c83b1e23445b98d830f9955177a446.
- Owned reports: build/security/stages/s20/s21/{containment,deployment}/conformance.json
  and build/security/sandbox/conformance.json. They retain securityAccepted=false,
  original-catalog/production-SLO limitations and exact revision/source/image facts.

This completes bounded S21, earning 0.875 planning weight under the unchanged
39.5 denominator: **56.0% complete / 44.0% pending, 21/33 stages delivered**.
**0/7 full roadmap phases are accepted.** A declared target class is not automatic
conformance or business finality. Arbitrary target frameworks, native MCP business
closure, other runtime tiers and full distributed/kill/partition catalogs remain
unqualified. S22 is next: generalized revocation/dispatch, connectivity-class SLOs
and the complete phase-3 exit matrix, including S10 aggregation requirements.

Fresh schemas/current contracts only: no database migrations, conversion,
backfill, legacy support or backward compatibility. The preserved revision-10
plan and baseline weights are unchanged.

#### S21 hosted CodeQL fixture repair — 2026-11-06

PR #279 head 9ff44fca passes hosted foundation, all product images including
LangChain, source scans, coverage and Sonar. CodeQL reports two high findings
in negative-test fixtures: generating a 1024-bit private RSA key, and building
an HTTP request to a non-loopback example host. No finding is waived or excluded.

The verifier test now derives undersized public-only material from a strong
public key and still asserts refusal before use; it generates no weak private
key. The HTTP permit refusal uses a loopback address and still proves that even
loopback HTTP cannot receive a target permit. It sends no request. Production
verification and transport behavior are unchanged.

Validation: the full shared SDK suite passes 42 cases with owned PostgreSQL and
zero skips, including the verifier's five cases. The exact private-permit Rust
refusal case passes. Hosted CodeQL reevaluation must follow the repair head;
no passing result is inferred from these local tests.

Known tested parent: 9ff44fca3cc80949654020b5117be9dcc830bc69. Changed fixture
SHA-256 (sorted paths, NUL, full bytes): b9d0dbbe67009da90cf8bf3211919b7f66daf26a27efc106af179575866fc81f.
No stage weight changes: 56.0% complete / 44.0% pending, 21/33 stages, 0/7 full
phases accepted. S22 remains the next complete implementation stage.
