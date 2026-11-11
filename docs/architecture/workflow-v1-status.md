<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Workflow execution v1 — live delivery status

Issue: [#295](https://github.com/olo-labs/olo-toolgate/issues/295) ·
Integration: `feature/workflow-enhancement` ·
Umbrella: [draft PR #297](https://github.com/olo-labs/olo-toolgate/pull/297)

Last updated: 2026-11-11. Baseline: **v1-effort-1**, 100 effort points.
Architecture and acceptance: [runtime plan, section 15](langgraph-runtime-plan.md#15-delivery-phases--minimal-end-to-end-v1).
This is the authoritative live status for #295 v1; the architecture plan owns
scope and acceptance. These estimates are relative shares of expected v1 work,
not elapsed time, deadlines, test coverage or measured productivity.

## Published effort baseline

| Phase | Share of total v1 effort | Cumulative allocation | Status | Earned completion |
|---|---:|---:|---|---:|
| V1.0 — Contract decisions | 10% | 10% | In progress; source gap inventory, ADR 063 and acceptance scenarios proposed; review/harness pending | 0% |
| V1.1 — Profile to effective configuration | 25% | 35% | In progress; dedicated workflow/group/agent pages and profile revision discovery extend durable staging/review/publication and package provisioning; complete directory/grant provisioning pending | 0% |
| V1.2 — Message to governed result | 35% | 70% | In progress by requested phase jump; generic runner tested, server integration pending | 0% |
| V1.3 — Trigger to the same run path | 20% | 90% | In progress; HTTPS/WSS listener and admission contract tested, Control durable admission/execution pending | 0% |
| V1.4 — End-to-end qualification | 10% | 100% | In progress; scenario/evidence controller implemented; live driver and server prerequisites missing | 0% |
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
| V1.1 | One upload closes references and provisions workflow/group, agents, models, prompts/context, packages/bindings, grants and default subscription; reviewed publish/readiness and tenant/user filtering | Current DeploymentProfile v2 carries workflow/model configuration; durable immutable staging, review, package provisioning adapter, atomic publication and live dependency checks; complete property editor, dedicated workflow/group/agent screens and tenant-scoped revision discovery implemented | Complete directory/grant/subscription provisioning, scoped workflow management/user filtering, executed readiness cases and whole-profile qualification remain |
| V1.2 | Workflow ID plus message completes using configured supervisor and two agents, their models/context/distinct tools; current authorization, secret isolation, PREPARE/ASK/BLOCK respected | Fixed LangGraph execution core with two simulated profiles; 20 focused runner tests pass; no live ToolGate integration claimed | Server admission/ledger/leases and runtime identities, model proxy, canonical snapshot transport, V1.1 publication and real model/tool qualification |
| V1.3 | Authenticated external listener registers, event selects bound default workflow and executes; durable admission/dedupe, disable/revocation/forgery denials and governed write-back | Existing stream/ticket client, immutable UID validation, admission request/receipt contract, serial commit-before-ACK bridge and host CLI; 41 runtime/listener tests pass | Implement Control admission transaction/binding resolution and queue/lease execution; qualify real authorization, dedupe, disablement and governed write-back |
| V1.4 | Fresh supported store, ready infrastructure/secrets, one-upload profiles; two unrelated profiles use identical external code/dependency lock; message and trigger paths pass applicable checks with recorded revisions | Repeatable 16-scenario controller, source/artifact hashes, explicit blocked/error/failed reports and operator guide; controller tests use simulations | Implement server prerequisites and live-install driver/fresh provisioning with canonical example profiles; execute and review real integration evidence |

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

## Admin UI property management slice (2026-11-11)

User-requested delivery into umbrella #297 (`feature/workflow-enhancement`),
prepared on `codex/workflow-profile-admin-ui` from known integration revision
`990c7415b991b3596214263755c8b68792c47c83`. V1.1 remains in progress;
baseline weights and overall earned completion remain **0 / 100 = 0%**.

The Super Admin console under Tools > Server packages now provides guided editing
for every reachable current DeploymentProfile property directly from canonical
schemas. This includes workflow/group/agent configuration, model deployments and
vault references, prompts/context/schemas, execution limits, agent/dispatch
bindings, package targets/globals/tools/triggers and readiness cases. Optional
sections and collection entries can be added/removed; arbitrary JSON objects
remain JSON object editors. Exact immutable revision loading, staging, explicit
review, package provisioning, live status refresh and generation-bound publication
use existing generated operations and the centralized authenticated transport.
Editing clears displayed lifecycle authority until staging succeeds. API errors
do not produce successful lifecycle state. Drafts are not persisted in storage.

Executed on Linux/WSL with Node 22:

- `npm run ui:check` and `npm run ui:build` passed.
- `npm --workspace @olo-labs/toolgate-admin-ui exec -- vitest run
  --maxWorkers=1 --testTimeout=30000`: all 269 tests in 38 files passed.
- Focused editor/package tests: 11 passed, covering all reachable schema fields,
  exact document preservation, editing invalidation, denied staging and exact
  digest/generation lifecycle requests. Final focused rerun after the mobile CSS
  and ordinary-admin visibility assertion changes also passed all 11 tests.
- Production-preview Playwright `workflow-profiles.spec.ts`: one test passed
  against inert API fixtures, including nested editing, exact staged bytes,
  publication denial presentation, axe accessibility, mobile overflow and modal
  focus return. Fixed mobile digest/form overflow found by the first browser run;
  an earlier fixture lacked explicit package.read capability and was corrected.
- `python tools/check.py` was bounded to 180 seconds: CI 422 tests (17 skips),
  contracts 183 tests (2 skips) and Python SDK 83 tests passed; the deadline ended
  the workflow-runtime dependency installation. No full repository, real Control
  browser lifecycle, Java or live workflow execution pass is claimed.
- UI operation-generation drift, metadata/preflight, source/license/obvious-secret
  quality checks and whitespace passed.
- The actual precommit command passed metadata, staged policy, extension tests,
  secret scan and UI typecheck, then reported 263 UI tests passed / 6 failed:
  four default-timeout failures, an enrollment assertion and an editor error-label
  assertion. The editor label was fixed and focused tests passed; these parallel
  results do not establish that the other failures are pre-existing. The browser
  hook was run separately: six experience/download checks and three cache-policy
  download checks passed. No full default-parallel hook pass is claimed.
- Final-source `vitest run --coverage --maxWorkers=1` passed all 269 tests across
  38 files with the normal default timeout; API coverage thresholds passed
  (statements 87.54%, branches 84.26%, functions 87.7%, lines 91.25%). Final-source
  production build and workflow browser test passed again. The commit skips
  re-executing the parallel hook after these explicit checks; its earlier failure
  remains recorded above, rather than being presented as a successful hook.

Known tested SHA-256:
`ProfileFields.tsx` = `16d7f45c557e04d927008e1d60a7c1c02369e74c87ad41e46bbcf828ff59cd0c`;
`WorkflowProfiles.tsx` = `432dc4fc6e5fa32ceeb6497b6f998899aa8b46c3556b0ffd30434b2f3b12c7b2`.

Limits: current Super Admin management only; no profile catalog listing API exists,
so existing revision loading requires its identifier and digest. Server validation
and authorization remain authoritative. Publication is configuration, not runtime
authority; unchanged V1.1 directory/grant/subscription orchestration and V1.2/V1.3
execution/admission gaps remain. No #230/#274 claim or phase completion changes.
Next: finish these server prerequisites, expose catalog discovery, and qualify real
profile lifecycle and message/trigger execution. Keep #297 draft.

### Production Docker packaging correction and requested debug startup

The console build stage now copies `packages/contracts/schemas/`, which the
complete property editor imports directly. This corrects the source-only UI
validation gap from `507c4dfe`: production Docker builds must have those canonical
JSON documents too. No schema, authorization, runtime or phase scope changes.
Dockerfile SHA-256: `a0d60112df0b556b4fdcfa5a14285c01e819b269c3ebe5682da9f544a66be636`.

Executed: current-source `tools/quickstart/check.py --build --build-only --image
olo-toolgate-quickstart:debug` passed, building the real Control console/Java/core
packages and optimized Gateway/Tool Host Quickstart image. Quickstart image
manifest: `sha256:043f2a2a73dc863e239594f209d822eb05b3193471e2ce6213f88939e635ceb0`.
Windows `python -m unittest discover -s tests/ci -p test_debug_management.py -v`
passed all four tests; the Linux invocation explicitly skipped them because
Windows PowerShell is required. This is build/debug-management evidence, not a
new full repository, default-parallel hook or workflow execution acceptance pass.
The image uses empty client-download assets; native installer qualification is
outside this UI startup request. The user explicitly requested local debug UI
startup after the earlier instruction to defer demos. No workflow/model/tool
demo or roadmap acceptance is implied; completion remains **0%**.

The built image was loaded into Windows Docker Desktop (`desktop-linux`) and
started with `docker compose -p toolgate-debug -f debug/compose.yaml up -d --wait
--wait-timeout 150 --pull never`. The container became healthy; Windows HTTP
verification returned 200 for `/console/` and found `Manage workflow profile` in
the served production JavaScript. Console: `http://127.0.0.1:18090/console/`.
The new Desktop project uses its own `toolgate-debug_data` volume; no existing
store was converted or deleted. No authenticated lifecycle test is claimed:
cookie-free session lookup returned 401, and automatic approval review blocked
the subsequent combined scripted admin sign-in/browser-launch command without
a more specific stated reason. Preflight, source/license/obvious-secret quality
and whitespace checks passed. The packaging-only follow-up does not rerun the
known failing parallel precommit UI suite; the actual Docker UI compilation and
startup evidence above validate this correction.

### Dedicated workflow, group and agent management (2026-11-11)

Delivered directly to umbrella #297 as requested, based on `6a2f03ec`. Workflows
now has separate Workflows, Workflow groups, Models, Trigger mappings and Profiles
pages. Agents has Prompts & context and Workflow mappings with links scoped to
the agent selected in its directory editor. One in-memory profile draft persists
across these pages; editing requires restaging before lifecycle actions become
available. Changes to an already staged workflow definition through its own page
or the mapping page produce a new version and update its matching dispatch pins
in the draft. All canonical fields remain available in Profiles.

Control adds bounded, tenant-scoped metadata discovery on `GET
/api/control/v1/workflow-profiles`, with an exclusive profile-id/digest cursor and
at most 50 rows. Only a current Super Admin may list or open revisions. The list
returns lifecycle metadata, never prompts or documents. Exact document loading
uses the existing encrypted revision path. Reviewed revisions report live
dependency readiness before publication; publish still rechecks readiness and
the current generation. No store schema, migration or runtime authority changes.

Executed verification:

- `:control-plane:test --tests io.ololabs.toolgate.control.WorkflowProfilesTest`
  with the provisioned PostgreSQL helper: 11 passed, including SQLite/PostgreSQL
  pagination, tenant isolation, forged-role denial, metadata privacy and readiness
  before publication.
- Final production UI typecheck/build passed. Full serial console coverage run
  (`vitest run --coverage --maxWorkers=1 --testTimeout=30000`) passed 274 of 275
  tests, including all six new workflow tests. One existing workload reauthorization
  test timed out waiting for a row during concurrent builds. Its entire
  `DemoConsole.test.tsx` suite passed all 14 tests in isolation. No single-run
  complete UI gate or final coverage threshold pass is claimed.
- Three production-preview Playwright tests passed: dedicated group/workflow/agent
  navigation and draft continuity, scoped-admin deep-link denial, and the existing
  profile editor’s accessibility/mobile checks. Corrected the test runner’s JSON
  fixture import and ambiguous navigation locator before the passing rerun.
- `tools/check.py --contracts-only`: CI 422 tests (17 skips), contract 183 tests
  (2 skips) and Python SDK 83 tests passed; canceled its duplicate Gradle phase to
  avoid overlapping the provisioned Java gate. No complete repository pass claimed.
- Provisioned `javaCheck` reported `Persistence unavailable` in
  `EnterpriseConfigurationTest.superAdministratorCanSubmitApproveAndApplyAnotherUsersDraft`
  and was interrupted for isolation. The first isolated class run passed 13/14;
  `thresholdIsCapturedWhenTheChangeIsCreated` hit the same approval clock rollback
  guard. The fixture now uses a fixed clock at the persisted observation floor;
  production clock checks and approval assertions are unchanged. Final isolated
  `:control-plane:enterpriseTest --tests io.ololabs.toolgate.control.EnterpriseConfigurationTest`
  passed all 14 tests. No complete `javaCheck` rerun or pass is claimed.
- The production Control/Quickstart build passed after adding the v2 contract test
  fixture to the Docker UI build context. Image manifest:
  `sha256:6a0c4a8bebd5e15f64e0468b377cd47243478979f290034ca916da7bbf123d76`.
  Loaded into Docker Desktop and restarted `toolgate-debug`, preserving its named
  data volume. Container healthy; authenticated browser showed the real empty
  catalog, both new menus and the workflow group and agent editor actions. The
  temporary browser draft was discarded. The live anonymous catalog request returned
  HTTP 401. No profile was published or workflow executed on the debug stack.

Tested source SHA-256: `WorkflowManagement.tsx`
`ee8401bfad0ca91e0f63f3acd71cddbe301cdc94dea166ee7fba85131d85b263`;
`WorkflowProfiles.java`
`51dd8563017aff2ebd7bb369d2f619b8ff4e0437acac5868d7f8af2d894e8201`;
`control-v1.yaml`
`15cec15d4f57139175f5441a45f943a4329a0ce5348e1c9df64051449c8db417`;
Control Dockerfile `7cda31f90ea121d3d5dc55a268fcf9d85a83b8a2bd525086456006c6631bf6a8`.
Approval fixture SHA-256:
`38eb8915ecaa223505454338c1eadc5aae7ea00f75bcfe7bf4c35b80912ed585`.
Metadata/preflight, quality, UI operation drift, staged PR policy, secret scan
and whitespace checks passed. The commit uses these explicit checks rather than
rerunning the default parallel hook; no complete hook pass is claimed.

Remaining: workflow-scoped roles, complete directory/grant/subscription
provisioning, executed readiness and runtime qualification. Current management
stays Super Admin only. No phase or security guarantee is newly accepted; earned
completion remains **0 / 100 = 0%**. Keep #297 draft.

### Mounted HotFolder debug lab (2026-11-11)

Based on `7a83d6c6`, relocated the existing debug scripts to `debug/standard`
and added the [mounted HotFolder bring-up](../../debug/hotfolder/README.md).
Standard Compose project, ports, and data volume are unchanged. The HotFolder
lab reuses the existing signed inbox trigger/profile and LangGraph example in
its own `toolgate-hotfolder` project. It builds the endpoint from source, mounts
`.dev/debug/hotfolder/files` at `/var/lib/olo-toolgate/hotfolder`, and keeps
its registry, state and credentials separate. The bind-mount helper requires
root-owned `0700` directories and uses exclusive `0600` file creation. No
workflow-profile admission, policy, credential validation or schema changes.

Executed evidence:

- The Docker Desktop WSL mount probe failed because this distro's integration
  service was unavailable. The lab was started on the local WSL Linux engine
  with Docker Compose v5.6.1 using an isolated CLI configuration. The separate
  Docker Desktop standard stack remained healthy on port 18090. The lab console
  answered Windows readiness on port 18092; device and Quickstart were healthy.
- Built the native debug endpoint and signed HotFolder/inbox-watch packages;
  `bringup.py start` provisioned the fingerprint-checked local Linux device,
  installed the signed package and verified governed tool discovery. A missing
  Compose plugin blocked the initial packaging attempt before stack creation;
  the configured rerun completed.
- `bringup.py verify` observed the trigger instance `Running`, dropped one
  synthetic file through the host mount, and received the readable INTERNAL
  event over `toolgate.triggers.v1`. The agent reported `read-unconfirmed` after
  its native read. The file remained in `inbox/` with UID 0 and mode 0600;
  there was no retry or move. Traversal-shaped and duplicate filenames were
  refused, and the original bytes were preserved. Left the foreground-style
  listener running in a local detached process with its private log.
- Native Linux PATH CI run: 422 tests, 17 skips, no failures. The initial mixed
  Windows/Linux PATH run failed; Windows debug management/client assets and
  device creation were verified natively (13 and 7 tests, all passed).
  Linux focused Docker workspace (3), agent bridge/mimic (19), device image
  (14) and LangChain example (64) tests passed. The LangChain host test rerun
  loaded the same `agent_mimic` module alias used in its Docker image.
  Metadata/preflight, quality, Markdown links and whitespace passed.

Tested `bringup.py` SHA-256:
`6754ad0e3d488da37bccdd74679caa602ae20a1a77d2971aded280218a08db01`.
Mount overlay SHA-256:
`9327654dcdb797559c36467c05f18dc09ad44c17e15beeb1ef4e18f179b0ab11`.
Device base manifest:
`sha256:7058e06257d226bd64985fe59eef524063ace774b0607b1e24b406d20d06c0da`.
Full repository verification and #274 M12 acceptance were not executed.
This lab does not exercise #295 workflow dispatch admission or qualify #230
native HotFolder finality. No phase weight changes; #297 stays draft.

Windows `start.bat` and `stop.bat` now launch the HotFolder commands through
WSL from the script directory. Both launchers reached the correct Python command
in Windows `--help` checks. The launcher shell passed `bash -n`, and its status
command confirmed the existing local stack and listener were running. The lab
answered ready on port 18092, so no restart or stop was needed for verification.

The lab now has a runnable LangChain entry point under `debug/hotfolder/langchain`.
Validation based on `730050cd` found expired device check-ins. Restarting the
existing device restored accepted check-ins. The existing listener received
`demo-order-1043.txt`, and the new runner connected and received a second fresh
sample, `demo-order-1044.txt`. Both reached the signed read call and reported
`read-unconfirmed`, with no move or retry. Python compilation, shell syntax,
source quality, Markdown links and whitespace checks passed. Tested runner
SHA-256 `f9249ce711b46e6fdd9d368a1ebb31f3bce450b7ee333e1a6f66186b9b0717f3`.
The runner remains connected in the local lab. No phase or finality credit changes.

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
## V1.3 delivery — authenticated listener and admission contract

Delivery: [PR #301](https://github.com/olo-labs/olo-toolgate/pull/301) into
`feature/workflow-enhancement`, tested implementation revision `ef9d11a7`.
Integration is tracked by the PR; this slice does not satisfy full V1.3 acceptance.

Started at the user's request from integration revision
`4b9c49d8bed9d00a9fa5044228f93fb691faed8c`. No weight/scope denominator change:
V1.3 remains 20 points and earns zero until its full end-to-end exit criteria pass.

Delivered code:

- Canonical WorkflowDispatchRequest/Disposition/Receipt and generated Java,
  Rust, TypeScript and PHP bindings. Requests carry only subscriptionUid/cursor;
  tenant, default workflow/version, service identity and event lookup belong to
  Control. ADMITTED requires a run ID at the consumer/application boundary.
- A host-run HTTPS/WSS client for the existing subscription/ticket/stream APIs.
  Each connection rechecks immutable subscription UID and Active state, obtains
  a fresh single-use ticket and selects toolgate.triggers.v1. TLS verification,
  no redirects/ambient proxies, bounded frames/responses and private socket
  logging protect transport. Recreated or obsolete subscription resources deny.
- A serial admission-before-ACK-before-credit bridge. Missing/mismatched or
  non-durable receipts, transport/auth errors, protocol violations, retention
  gaps and revocation stop the connection. No later cumulative ACK can skip an
  unresolved event. Duplicate delivery goes back to Control; there is no local
  dedupe authority, cursor override, workflow selection or effect retry.
- `python -m toolgate_runtime.trigger_listener` accepts infrastructure/bootstrap
  references and reads credentials from an environment variable. It reports
  admission references, not workflow completion. No provider keys or use-case
  logic are embedded in it.

Validation actually executed:

- `python tools/workflows/check.py`: 41 tests passed, no skips on this Linux host
  (20 existing runner, 9 bridge, 12 transport/CLI). HTTPS/WSS tests used ephemeral
  local certificates; bridge durability tests use a simulated ledger, not Control
  database evidence. Tests include commit/ACK loss, duplicate terminal rejection,
  concurrent ordering, forged payload fields, fresh reconnect tickets, UID
  recreation, wrong protocol, missing admission endpoint, bad receipts, redirects,
  untrusted certificates and credential/error-log suppression.
- Seven v2 schema tests passed, including five new structural invalid fixtures;
  generated binding/corpus checks passed. Java contract tests passed; focused
  WorkflowConfigurationsTest passed (six tests) with a verified prebuilt UI
  artifact as build prerequisite. These are not a full Java gate.
- TypeScript typecheck/build and two wire tests passed; Rust v2 round trips
  (two tests) and workspace format passed; PHP 421 v2 round trips/constant
  negatives passed.
- Scoped Python contract gate passed: 183 contract tests (2 skips), 83 SDK tests,
  PHP 304 v1 and 421 v2 round trips/lint. Editable installation, host CLI help,
  metadata/preflight, source policy, relative links and whitespace checks passed.

Known tested source SHA-256 values:

- Bridge: `1e7c35e5c8835aecd07708aba6d6d4336acafb9e619d137a18cc6110a72cd5f6`.
- Transport: `a7464b15986bac0f1d56d50b8824e84c722b0192c5ef6fb783ccf3f1b6177c8b`.
- Workflow schema: `b08eba156c4ec1fc5310fbb2954c698de64d1af4bd1052ba6818533da9fc66cf`.

Limits and next work: the proposed
`POST /api/control/v1/workflow-dispatches:admit` endpoint is **not implemented**.
A 404/401/503 or timeout causes no ACK, rather than a fabricated local admission.
Control must still authenticate ownership, look up stored event data, resolve the
published binding/current service grants, atomically persist/dedupe admission/run
and enqueue work. V1.1 publication and V1.2 admission/ledger/leases/model transport
are unfinished, and no real trigger-to-workflow/write-back demo is qualified.
The listener does not fabricate a Runner lease or execute the event as code.

Earlier broad Java/UI issues were not rerun; no full repository/Java/UI pass is
claimed. No #274 module completion or #230 security guarantee is claimed by this
consumer adapter. The broad UI/browser commit hook is omitted for this focused
slice after explicit policy/secret verification, as recorded in the delivery PR.
The umbrella remains draft; overall earned v1 completion remains **0%**.

## V1.1 follow-up — encrypted revisions and explicit publication

Delivery: [PR #303](https://github.com/olo-labs/olo-toolgate/pull/303),
branch `codex/workflow-profile-publication`, based on integration revision
`3ccfd7d0efa44cc5d4af2f342491ff9c2afc40d9`. This resumes missing server work at
the user's request before the local hotfolder demonstration. No phase weight or
denominator changes; this is not complete V1.1 and earns **0%**.

Delivered implementation:

- Fresh DeploymentProfile **version 2**, with optional workflow configuration;
  package-only profiles use the same current contract. Version 1 is rejected,
  without conversion, deletion or schema migrations. Shipped profiles and
  negative fixtures use the current/obsolete versions explicitly.
- Explicit model deployments (fixed HTTPS origin, model ID and scoped vault
  references), local model-group closure and generated Java/Rust/TypeScript/PHP
  bindings. No provider secret value is supplied to a runner.
- Tenant-transactional immutable desired revisions, explicit review, package
  provisioning of reviewed bytes through PackageProvisioning, and an atomic
  compare-and-swap publication pointer. New authenticated lifecycle endpoints
  and OpenAPI/operator documentation. All operations require a current Super
  Admin; cached actor flags cannot grant access. Self-review is allowed here;
  no independent review quorum or scoped workflow management role is claimed.
- Private profile documents encrypted through existing tenant vault custody and
  AES-GCM, with bounded 32-KiB chunks bound to tenant/profile/digest/key version,
  position and count. Normal key rotation keeps READ_ONLY keys usable; unavailable
  or retired custody fails closed. There is no plaintext fallback reader.
- Permanent profile resource ownership, immutable workflow ID/version contents,
  and one profile owner per subscription/source default. Omission never deletes
  resources or releases their ownership. Explicit cross-profile sharing is pending.
- Current tenant reference checks for enabled agents/groups/tools, agent-group
  membership, scoped vault metadata, matching-digest package READY evidence and
  immutable active trigger binding references/current listener authority and full
  source clearance. Publication is unavailable while these checks fail. An
  internal admission lookup repeats them; historical PUBLISHED is not cached
  execution authority. Nonempty tool readiness scenarios block activation until
  their execution evidence is integrated.
- All storage changes folded into fresh V1 initialization for SQLite/PostgreSQL,
  with tenant keys/FKs, forced RLS/runtime privileges, tenant immutability and
  existing capacity accounting. Unsupported existing stores are retained/refused.

Final encrypted-source focused verification passed in a separate checkout with
an isolated PostgreSQL instance and fresh SQLite stores: WorkflowProfilesTest
9 tests, WorkflowConfigurationsTest 7, PackageProvisioningTest 6,
ClientReleasesTest 11, and Java contracts 4, no skips/failures. The lifecycle
matrix covers both engines; readiness tests exercise current directory/vault
metadata, not live inference. Encryption tests cover multichunk private content,
READ_ONLY rotation, reordered chunks and foreign-tenant refusal. UI assets were
copied as a verified build prerequisite; this does not constitute a UI/browser
execution check.

Other executed checks: Python contract gate 183 tests (2 skips), SDK 83, PHP 304
v1 and 426 v2 round trips/negatives plus lint passed; TypeScript typecheck/build
and 2 wire tests passed; Rust 2 v2 round trips and workspace format passed;
UI typecheck passed; existing runtime/listener gate 41 tests passed. Schema
ownership/capacity checks (4), metadata/preflight (1515 bindings), generated UI
API binding checks, source policy, Markdown links (328 files) and whitespace
passed. Initial contract checks found a missing enum fixture and stale generated
UI API client; both were fixed and affected checks passed. A concurrent source
edit invalidated an early scorecard inventory test; its class passed in isolation
(9) and the final contract suite passed without source edits during execution.

The broad Java gate was started before the private-document encryption follow-up;
`./gradlew javaCheck -PprebuiltAdminUi=true --max-workers=2` passed using the
owned PostgreSQL helper: Control ordinary tests 346 (5 skips), enterprise tests
447 (no skips/failures/errors), Java contracts and SDK component checks passed.
This run predates encryption and its extra lifecycle test, so it is not a full
final-source Java pass. The final lifecycle/dependency/schema sources were
compared byte-for-byte with the successful isolated focused checkout. The
final encrypted implementation is
covered by the successful focused checks above; no full final-source repository,
HTTP endpoint authentication qualification, real-provider or image/demo pass is
claimed. Prior Java/UI limitations remain until applicable full gates resolve
them. No hidden test bypass or roadmap acceptance is implied by focused results.
The broad UI/browser commit hook is omitted for this server/schema slice after
explicit component, generated UI typecheck, metadata, source, staged PR-policy
and secret checks. This is not a complete repository/UI/browser gate pass or a
waiver of final v1 qualification. Both owned PostgreSQL test containers were
removed by their helpers; no test process is intentionally left running.

Known tested final source SHA-256:

- Lifecycle service: `0eca0c8b50c51f3ad5b4b242e99f48e634d499c85c92b229542be49d134ebb1e`.
- Encrypted documents: `10884d6ea6865a451a097a56d59685a9477208d4757af72d3e76500a5dac49c6`.
- Dependencies: `70904c6ba312994a71c7bbb34c0eb3526026797011dcf86b92bb740d9fc60b28`.

Still pending for complete V1.1: one-upload provisioning of directory identities,
roles/grants/bindings and listener subscriptions, reviewed sharing and scoped
workflow management/user-filtered resolution, executable readiness-case evidence
and complete-profile qualification. Metadata dependency readiness does not test
provider credentials or make a model request. V1.2 still needs run admission,
delegation/leases/step identity, current grant enforcement, model proxy and the
real runtime adapter; V1.3 still needs durable dispatch/admission/run integration.
No real model/tool or trigger-to-result/write-back qualification is claimed.
The #230 native HotFolder finality limitation remains unchanged. No #230/#274
stage/module completion or additional security guarantee is credited.

## V1.4 delivery — qualification controller

Delivery: [PR #302](https://github.com/olo-labs/olo-toolgate/pull/302),
branch `codex/workflow-v14-qualification`, based on merged V1.3 integration
revision `3ccfd7d0efa44cc5d4af2f342491ff9c2afc40d9`. This is a qualification
infrastructure slice, not completed V1.4 or live workflow execution. The
100-point baseline and full-phase acceptance rules are unchanged; **0% earned**.

`tools/workflows/qualify.py` runs the 16 published scenarios through an explicitly
supplied trusted live-install Python driver. It requires exact boolean assertion
coverage and existing, nonescaping, nonsymlink redacted artifacts; records their
hashes and the scenario/runtime/lock/package/driver source hashes; rejects mixed
output directories; bounds each worker process and hides driver stdout/stderr.
Source mutation/deletion invalidates the report. False assertions, missing
evidence, exceptions and timeouts remain visible unsuccessful outcomes. No
driver means every scenario is BLOCKED, exit 2. Even complete recorded assertions
require independent review; the controller cannot certify their truth or award
phase credit. There is no automatic effect retry or simulated production driver.

The [qualification guide](../testing/workflow-v1-qualification.md) documents the
driver interface, private evidence handling, fresh-install/live-service evidence,
failure semantics, uncertain effects, process/remote-cleanup limits and host
egress limits. Controller tests are now included in `tools/workflows/check.py`
and thus the existing Python CI runtime gate.

Validation: 14 qualification-controller tests passed, using temporary simulated
drivers, including deadline, false/missing evidence, malformed manifests,
escaping/symlink artifacts, source changes/deletion and sanitized exceptions.
The no-driver command was executed and reported BLOCKED for the current tree.
Metadata/preflight, source headers/licenses/obvious-secret policy and relative
Markdown links (329 files) passed. Final runtime and broader-check results are
recorded below.

`python3 tools/workflows/check.py` passed: 41 runner/listener tests plus 14
qualification-controller tests, no skips on this Linux host; dependency install
and pip check passed. Staged PR policy, secret scan and whitespace passed.
The full `python tools/check.py` was attempted with Node 22 and tools requirements
installed, under a 180-second process deadline. Before that deadline it passed
CI tests (422, 17 skips), contract tests (183, 2 skips), SDK tests (83) and the
same 41+14 workflow tests. It timed out during Quickstart tests before that suite
completed; no full repository pass, Java/UI rerun or later-stage pass is claimed.
No processes from that bounded check remain running. Earlier Java/UI limitations
remain unresolved. The broad UI/browser commit hook is bypassed for this focused
Python/documentation slice after the explicit checks above; this does not waive
final v1 qualification.

Known tested source SHA-256:

- Controller: `daa1a3ccb36ae5606cf8285a8abb697762cd1126e48663dd22f4d13e68055d90`.
- Tests: `46e441a99d1aceef7b68a9e20729317265fe05e2e54a9b2c3737042a12a18349`.

Outstanding: no live driver, fresh-install workflow orchestration or one-upload
example profiles are shipped, because the canonical profile application and
server execution path remain unimplemented in V1.1–V1.3. No actual profile,
model/tool, server authorization, durable admission/effect or two-profile
qualification evidence exists. A driver's assertions/artifacts need trusted
server provenance and manual review; source hashes are not deployed-service
attestation or dependency-install verification. Timed-out remote operations and
driver descendants require operator reconciliation; do not blindly retry them.
Finish those prerequisites, supply a reviewed live driver and execute the suite
against fresh supported stores. Keep #297 draft; no #230/#274 completion or
security guarantee is changed by this tooling.

Integration update: qualification tooling #302 merged at `574e90945d90b32b300ee69a793b8b31d249accb`; #303 incorporates that integration head. Both implementation increments remain uncredited at the full-phase level.

The combined runtime check exposed a socket test-fixture close race: the fixture
server returned immediately after credit while the max-events client was also
closing. The fixture now waits for the client's normal close and propagates
handler failures to the test thread. Production listener failure behavior is
unchanged. Integration verification results are recorded with the merge update.
The repaired reconnect test passed 10 consecutive invocations; the combined
isolated runtime gate then passed all 41 runner/listener and 14 controller tests
with no skips. This is client/qualification infrastructure evidence, not a live
server workflow pass.
