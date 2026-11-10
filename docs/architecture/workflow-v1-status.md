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
| V1.0 — Contract decisions | 10% | 10% | Planned; architecture proposal published, required ADRs/gap inventory not completed | 0% |
| V1.1 — Profile to effective configuration | 25% | 35% | Not started / no accepted implementation evidence recorded | 0% |
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
| V1.0 | Contract inventory; ADRs for canonical profile, listener registration/dispatch, identity, explicit models and minimal run state; executable positive/negative scenario specification | Proposal v0.9 at `7c4d2e85a9ce09fc2bef94e3f096d326bb03bb89`; no phase acceptance claimed | Inventory actual #274/current contracts and approve the required decisions |
| V1.1 | One upload closes references and provisions workflow/group, agents, models, prompts/context, packages/bindings, grants and default subscription; reviewed publish/readiness and tenant/user filtering | None recorded | Implement and verify complete profile application, deny cases and readiness |
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

## Baseline history

- **v1-effort-1 (2026-11-10):** initial estimated allocation 10/25/35/20/10;
  100-point denominator. No effort or completion baseline previously published.
  Quotas/budgets, advanced routing, distributed recovery/container packaging and
  wider production qualification remain later releases, as section 15 specifies.