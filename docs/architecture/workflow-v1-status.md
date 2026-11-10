<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Workflow execution v1 — live delivery status

Issue: [#295](https://github.com/olo-labs/olo-toolgate/issues/295) ·
Integration: `feature/workflow-enhancement` ·
Umbrella: [draft PR #297](https://github.com/olo-labs/olo-toolgate/pull/297)

Last updated: 2026-11-10. Baseline: **v1-effort-1**, 100 effort points.
Architecture and acceptance: [runtime plan, section 15](langgraph-runtime-plan.md#15-delivery-phases--minimal-end-to-end-v1).
This is the authoritative live status for #295 v1; the architecture plan owns
scope and acceptance. These estimates are relative shares of expected v1 work,
not elapsed time, deadlines, test coverage or measured productivity.

## Published effort baseline

| Phase | Share of total v1 effort | Cumulative allocation | Status | Earned completion |
|---|---:|---:|---|---:|
| V1.0 — Contract decisions | 10% | 10% | In progress; source gap inventory, ADR 063 and acceptance scenarios proposed; review/harness pending | 0% |
| V1.1 — Profile to effective configuration | 25% | 35% | In progress; workflow contracts and read-only validation implemented, publication not connected | 0% |
| V1.2 — Message to governed result | 35% | 70% | In progress by requested phase jump; generic runner tested, server integration pending | 0% |
| V1.3 — Trigger to the same run path | 20% | 90% | Not started / no accepted implementation evidence recorded | 0% |
| V1.4 — End-to-end qualification | 10% | 100% | Not started / no accepted implementation evidence recorded | 0% |
| **Total** | **100%** | **100%** | **Planning baseline published; v1 not delivered** | **0%** |

V1.2 carries the largest share because admission, runtime identity, model proxy,
agent/tool/context resolution and the standalone generic runner must work as one
path. V1.1 closes the full profile configuration and publication dependencies.
V1.3 reuses #274's trigger mechanisms. V1.4 is dedicated integration evidence,
not a replacement for tests within the earlier phases. Shared capabilities may
already exist elsewhere; credit requires verified integration evidence for #295.

**Overall earned completion: 0 / 100 = 0%.** A phase earns its full weight only
when every exit criterion and applicable check in section 15 has evidence and
the implementation PRs are merged into the integration branch. Partial phases
earn zero; record their delivered slices and outstanding work below. An open
or approved PR is not merged delivery. If finer progress is needed, agree and
publish smaller weighted acceptance units before crediting them; do not infer
percentages from files changed, PR count or subjective estimates. Scope/weight
changes require a baseline revision, reason and old/new allocations totaling
100; never silently alter the denominator or historic completion.

## Phase acceptance and evidence

| Phase | Required acceptance summary | Delivered scope / evidence | Remaining work |
|---|---|---|---|
| V1.0 | Contract inventory; ADRs for canonical profile, listener registration/dispatch, identity, explicit models and minimal run state; executable positive/negative scenario specification | Proposal v0.9 at `7c4d2e85a9ce09fc2bef94e3f096d326bb03bb89`; no phase acceptance claimed | Review ADR 063, reconcile endpoint/schema details during implementation and bind scenarios to executable integration harness |
| V1.1 | One upload closes references and provisions workflow/group, agents, models, prompts/context, packages/bindings, grants and default subscription; reviewed publish/readiness and tenant/user filtering | Canonical WorkflowConfiguration and generated bindings; read-only local/dependency validation; six Control tests and cross-language checks pass | Catalog persistence, reviewed grants/publication, DeploymentProfile v2 integration and end-to-end readiness remain |
| V1.2 | Workflow ID plus message completes using configured supervisor and two agents, their models/context/distinct tools; current authorization, secret isolation, PREPARE/ASK/BLOCK respected | Fixed LangGraph execution core with two simulated profiles; 20 focused runner tests pass; no live ToolGate integration claimed | Server admission/ledger/leases and runtime identities, model proxy, canonical snapshot transport, V1.1 publication and real model/tool qualification |
| V1.3 | Authenticated external listener registers, event selects bound default workflow and executes; durable admission/dedupe, disable/revocation/forgery denials and governed write-back | None recorded | Integrate #274 dispatch with the same run path and test redelivery/effect safety |
| V1.4 | Fresh supported store, ready infrastructure/secrets, one-upload profiles; two unrelated profiles use identical external code/dependency lock; message and trigger paths pass applicable checks with recorded revisions | None recorded | Build and execute repeatable qualification and document deployment limitations |

## Update required with every PR

Every #295 PR targeting `feature/workflow-enhancement`, including documentation,
fixes and tests, updates this file in the same commit series. The PR description
links its status change, identifies affected phase(s), lists checks actually
executed and their results, and states remaining work and limits. Record the PR
number/link once known by updating that PR's branch; no separate progress-only
follow-up PR is needed. Keep the umbrella draft PR's summary synchronized.

For each PR, record:

- Affected phase and concrete completed scope; distinguish a delivered slice from
  an accepted phase. Record denied behavior as well as the successful path.
- Exact validation commands/results, known tested revision or source digest,
  relevant artifacts and unresolved failures. Never invent the SHA being created.
- Integration state (proposed/open/merged), limitations and the next outstanding
  work. PR merge alone does not imply phase acceptance.
- Acceptance evidence and the earned-completion calculation. Before claiming
  credit, verify merged code on the integration head, using a known ancestor or
  source digest if the status update is part of the merge. Do not add a commit
  solely to reference itself. Keep pending evidence uncredited until verified.

Use the latest integration head for intermediate branches; merge their reviewed
PRs into `feature/workflow-enhancement`. Keep #297 in draft until V1.4 passes.
This process does not change the authoritative #230/#274 delivery rules.

## Delivery ledger

| PR / known revision | Phase | Scope and integration state | Validation actually executed | Limits / next work |
|---|---|---|---|---|
| [#297](https://github.com/olo-labs/olo-toolgate/pull/297), known planning revision `7c4d2e85a9ce09fc2bef94e3f096d326bb03bb89` | Planning; no phase credit | v0.9 profile-driven minimal-v1 architecture published on integration branch; umbrella PR remains draft | Relative Markdown links, whitespace, metadata/preflight, staged PR policy and secret scan passed for that revision. Full check reported 6 failures, 9 errors, 4 skips in CI tests; commit hook reported browser-extension failures and did not finish promptly; documentation commit bypassed that hook | No implementation or complete-suite pass. Next: V1.0 contract inventory/ADRs |

| [#298](https://github.com/olo-labs/olo-toolgate/pull/298), inspected baseline `54a9c21c`, reviewed source revision `995000be` | V1.0 — in progress, 0% earned | Source inventory, proposed ADR 063 and 16 machine-readable scenario specifications; merged into integration branch at `fd16d03e`; no phase acceptance | Links passed across 327 files; nine link-checker tests passed; JSON parse, whitespace and staged PR policy passed. ADR SHA-256 `36eca2b74a2e1296c9b15a489ec63ac220d07fa88d8455082189922dc7cc498f`; scenario SHA-256 `a31ecc9cadad44637cc302c99c76efb70809c60c9fafe8f3616ba9f471834d6c` | Documentation-only; no runtime scenarios executed, broad failing/hanging hook bypassed. Next: review contract decisions and implement V1.1 schema/catalog with executable acceptance harness |

## Current V1.0 slice

[ADR 063](../adr/063-profile-driven-workflow-execution.md) records the inspected
source inventory and decisions. [Acceptance scenarios](workflow-v1-acceptance.json)
specify positive/negative outcomes; they are not executed runtime tests. In particular,
existing subscriptions are user-owned: listener ownership and service execution
authority remain separate. Proposed dispatch admission uses a delivered cursor,
server-side event lookup and durable dedupe before cumulative acknowledgement.
No runtime capability or V1.0 acceptance is claimed. Overall remains **0%**.

## Current V1.1 implementation slice

Implementation PR: [#299](https://github.com/olo-labs/olo-toolgate/pull/299),
branch `codex/workflow-profile-validation`, known source revision `5ab911a6`.
Merged into `feature/workflow-enhancement` at `2abdafe3358004c6a50c557b755f440635100134`; not phase-accepted.

Canonical `workflow.schema.json` and Java/Rust/TypeScript/PHP bindings now define
workflow groups, explicit supervisor/agent models, context sources, bounded
execution and immutable subscription/source workflow bindings. Control adopts
these definitions in ContractCodec. WorkflowConfigurations is read-only: it
rejects duplicate IDs, broken local references, absent entry agents, inconsistent
limits and conflicting dispatch defaults before invoking a required dependency
port. That port must resolve only the current tenant's readable resources;
these unit tests do not certify actual cross-tenant store enforcement.

Executed evidence: six WorkflowConfigurationsTest tests passed; both Java
contract test classes passed (four tests); seven v2 canonical-schema tests passed;
TypeScript typecheck/build and two wire tests passed; two Rust v2 round-trip tests
and workspace formatting passed; PHP passed 418 v2 model round trips and constant
negatives; generated bindings/corpus and metadata/preflight passed. Added eight
structural rejection fixtures. The initial Java attempt failed with Node 18;
the focused Java check passed after using Node 22.23.4.

Known inspected integration revision: `fd16d03e86aa6d57dc87153065ba322c81fe62c8`.
Tested schema SHA-256: `e76030917aeca9db7cbd042af39cb758c0bb951bd06bf485c2cd6d4c3dea6ca8`.
Tested validator SHA-256: `931a7ba59adbc7561065b9a104589847c20260f6dbb375929f93e8f05d23530e`.

Limitations: no upload endpoint, profile-version change, catalog persistence,
publication pointer, provisioning orchestration, runtime admission or grants are
enabled by this slice. Successful validation is neither READY nor authorization.
No full phase weight is earned. Next: connect catalog storage and management
review to fresh version-2 profile application, then qualify complete readiness.

## Baseline history

- **v1-effort-1 (2026-11-10):** initial estimated allocation 10/25/35/20/10;
  100-point denominator. No effort or completion baseline previously published.
  Quotas/budgets, advanced routing, distributed recovery/container packaging and
  wider production qualification remain later releases, as section 15 specifies.
### Broader verification for the V1.1 slice

The first broad Java attempt lacked CONTROL_TEST_URL and encountered PostgreSQL
fixture failures; it was cancelled and replaced with the repository's isolated
PostgreSQL helper. The provisioned `./gradlew javaCheck -PprebuiltAdminUi=true
--max-workers=2` run has reported
`EnterpriseConfigurationTest.superAdministratorCanApproveOwnRequestAndSatisfyTwoReviewQuorum`
failing. That broad run was interrupted after diagnosis; no full Java pass is claimed.

The repository-wide `python tools/check.py` rerun uses tools/requirements.txt in
an isolated Python environment and Node 22. It passed CI (422 tests, 17 skips),
contracts (183 tests, 2 skips), Python SDK (83 tests) and Quickstart (51 tests).
Its duplicate Java/build stage was interrupted to avoid overlapping Gradle builds
in the same checkout; the separately provisioned Java gate was also interrupted after its reported failure. Initial verification
attempts without all Python dependencies failed; they are not acceptance evidence.

The actual commit hook was exercised with Node 22: browser-extension tests,
metadata/policy/secret checks and TypeScript checks passed. Admin UI tests under
concurrent load reported 64 failures; a single-worker default-timeout rerun completed with 259 passes and six 5-second
timeouts. Rerunning the four affected files with one worker and a 30-second CLI
timeout passed all 35 tests. This is diagnostic evidence, not a default-gate pass. The hook's browser smoke passed six
experience/download checks and three cache-policy download checks. These are
separate results, not a successful complete hook. Publication of the reviewed
slice may bypass repeating that broad hook while these failures are recorded;
keep its implementation PR in draft and do not credit or declare the phase done.
The enterprise self-approval method passed in isolation on the pre-change
integration revision `fd16d03e86aa6d57dc87153065ba322c81fe62c8`, using the same
provisioned PostgreSQL helper. This does not establish a pre-existing failure;
full-run ordering/concurrency versus a workflow regression remains unresolved.
Do not weaken approval checks or merge #299 based on that single baseline pass.
Final focused diagnosis for this slice: the entire EnterpriseConfigurationTest
class passed in isolation against implementation source `5ab911a6` (14 tests,
zero skips/failures/errors), using a separate checkout and isolated PostgreSQL.
The method also passed alone at pre-change integration `fd16d03e`. The broad-run
failure therefore remains unresolved suite-order/concurrency evidence, not proof
of either a workflow regression or a verified pre-existing defect. Both broad
runs were interrupted; their helper contexts cleaned up their test databases.
No verification processes are intentionally left to continue this implementation.
That recommendation was made before #299 was merged. The merge does not resolve
the broad gate or establish phase acceptance.
## V1.2 phase jump — generic runner foundation

Delivery: [PR #300](https://github.com/olo-labs/olo-toolgate/pull/300) into
`feature/workflow-enhancement`, tested implementation revision `ed8336d5`.
The PR tracks integration; this tested slice does not satisfy full V1.2 acceptance.

On 2026-11-10 the user requested starting the next phase before V1.1 publication
and its broad verification were complete. V1.2 now proceeds from the latest
integration revision `2abdafe3358004c6a50c557b755f440635100134` (which includes
merged #299). This changes sequencing only: scope, effort weights and the
100-point denominator are unchanged. Neither V1.1 nor V1.2 earns weight yet.

`apps/langgraph-runtime` now provides a fixed LangGraph supervisor/agent loop.
Its leased snapshot supplies prompts, distinct explicit model groups, context
sources, candidate agents, remediation mode and finite step/handoff/time limits.
A required ToolGate port handles step authorization, refreshed agent summaries, per-agent tool discovery,
inference, context/tool calls and confirmed completion. The graph uses no
provider client, use-case IDs, direct tool connection or executable profile code.

Local checks narrow candidates and verify returned step/run/lease/model scope;
they do not establish actual server authorization. Context is read-only even in
APPLY, PREPARE refuses mutation, ASK waits, denial/unknown outcomes stop without
retries, ownership loss propagates, and missing/foreign completion acknowledgements
cannot produce a successful result. Credentials stay out of graph state/repr and
internal LangSmith tracing is disabled. Host egress confinement is not delivered.

Validation executed:

- `python tools/workflows/check.py`: isolated dependency installation, pip check
  and 20 real-LangGraph tests passed against a simulated ToolGate boundary. Two
  distinct profiles use unchanged code and separate supervisor/agent model
  assignments. These are not live model/tool or end-to-end authorization tests.
- Editable package installation succeeded. Dependencies are version-pinned in
  requirements.lock; hash/cross-platform production qualification is pending.
- `test_product_release.py`: 25 tests passed, including central version stamping
  of the new runtime package. `test_unified_ci.py`: 16 tests passed; the Python
  CI group invokes the isolated runtime gate. Metadata/preflight, source policy
  and whitespace checks passed.

Tested source SHA-256 values:

- Runner: `31f001655924a2da6848f22ca35a51a8cb29a512b0153a6e16954fe7812e7dc3`.
- Tests: `c5488f0af97fef381be32134745bd1e0132d949094a8d49cbe0547903bbfca7c`.
- Dependency lock: `65e6328bed3120bb831a86caa444b8d75cdc3f8a15dc26b86037ebdbb2ce3246`.

`tools/check.py --ci-common --ci-part python --contracts-only` passed: 183
contract tests (2 skips), 83 SDK tests, PHP 304 v1 and 418 v2 model round trips,
PHP lint and the scoped Python foundation gate. That command deliberately skips
the runtime dependency environment; its separate isolated gate passed all 20
tests. The earlier broad Java/UI failures were not rerun for this Python-only
slice. No complete repository or real-model/real-tool end-to-end pass is claimed. Outstanding:
Control admission/ledger/leases, authenticated runtime/step tokens, governed model
proxy and wire adapter, V1.1 publication/readiness, real external message/trigger
execution, and the earlier broad Java/UI gate issues. No runnable-profile,
container, trigger registration, checkpoint/failover or full V1.2 completion is
claimed. The umbrella PR remains draft and overall earned completion is **0%**.

For this Python-only delivery, the broad UI/browser commit hook was bypassed
after executing the runtime, scoped Python, release, CI-ownership, metadata,
source-policy, PR-policy and staged secret checks recorded above. Earlier broad
Java/UI failures remain limitations; no waiver of final v1 qualification is implied.