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
| V1.2 — Message to governed result | 35% | 70% | Not started / no accepted implementation evidence recorded | 0% |
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
| V1.2 | Workflow ID plus message completes using configured supervisor and two agents, their models/context/distinct tools; current authorization, secret isolation, PREPARE/ASK/BLOCK respected | None recorded | Implement minimal governed run and unchanged external Python execution |
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
failing and is still running. No full Java pass is claimed.

The repository-wide `python tools/check.py` rerun uses tools/requirements.txt in
an isolated Python environment and Node 22. It passed the CI, contract and Python
SDK suites (83 SDK tests); remaining gates are still running. Initial verification
attempts without all Python dependencies failed; they are not acceptance evidence.

The actual commit hook was exercised with Node 22: browser-extension tests,
metadata/policy/secret checks and TypeScript checks passed. Admin UI tests under
concurrent load reported 64 failures; a single-worker rerun is in progress and
has also reported a Navigation timeout. The hook's browser smoke passed six
experience/download checks and three cache-policy download checks. These are
separate results, not a successful complete hook. Publication of the reviewed
slice may bypass repeating that broad hook while these failures are recorded;
keep its implementation PR in draft and do not credit or declare the phase done.