<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Native LangGraph execution with profile-owned JSON configuration

Status: Phase 1 implemented and locally accepted on `feature/native-langgraph-phase1`; hosted CI/release qualification remains separate. Phase 2 is not implemented.

Authoring decision: native LangGraph Python graph packages plus JSON configuration.
YAML is not part of this feature. This plan now ships with the Phase 1 implementation; earlier trigger/CI delivery excluded it.

## Delivery split: two phases

### Phase 1 — minimal complete execution, one PR

Target `feature/workflow-enhancement` from `feature/native-langgraph-phase1`.
Deliver a working vertical slice rather than all advanced contracts at once:

1. Required JSON graph selection pinned in the profile and claimed run.
2. Operator-installed, digest-checked native StateGraph factories, using the
   existing worker image/dependency set and fresh per-run governed adapters.
3. Preserve current run/step authorization, PREPARE, limits, result confirmation
   and fail-closed behavior. Do not replace those existing safety boundaries.
4. Basic text boxes to edit full profile JSON and workflow/agent sub-JSON in
   their existing pages. Explicit apply, format, invalid-buffer refusal and
   normal stage/review/publish are sufficient; no new canvas/editor framework.
5. Read-only per-configuration graph package mount and generic worker selection.
   Provision/install packages as an operator; no tenant Python upload service.
6. Execute two different configurations and trigger-to-configured-agent result
   flows using the same runtime image. Supply configuration/package READMEs,
   input/output instructions and evidence from actual Control-confirmed runs.
7. Focused contract, runtime, UI and stack acceptance checks in CI. Keep tests
   for unsupported package selection and actual denied/unknown tool outcomes.

Phase 1 exit: edit the relevant JSON in the UI, publish it, run the configured
native graph from a trigger, inspect its confirmed result, switch configuration
and run a different trigger/workflow without a per-workflow container rebuild.
No Phase 2 capability is advertised as implemented by that demonstration.

### Phase 2 — advanced capabilities and exhaustive qualification

Add durable checkpoint/restart/replica resume, native interrupts and approval
races, durable effect identities/ledger integration, advanced retries and
non-deterministic replay, untrusted-code isolation and tenant package publishing,
concurrent protected-step support, additional native entry-point forms,
monitoring adapters, richer schema-aware editors/graph preview, shared caches,
and exhaustive malformed-input/concurrency/cross-tenant tests. Retain unknown
outcome semantics; do not silently emulate these features in Phase 1.

Sections below describe the target design. Requirements dependent on those
advanced services belong to Phase 2 until the application implements them.

### Critique against the current application

The current Control ledger has one active protected step per run and no native
checkpoint/resume service. A custom TenantPostgresSaver, universal effect-key
scheme, global serializer policy and cross-tenant compiled-graph cache would
invent new boundaries before a basic native graph can run. Defer those details.
Use existing governed calls, serialize protected adapter operations within a
run, load fresh operator-installed graph definitions and refuse unsupported
checkpoint/interrupt capabilities. Ship the typed author API in the existing
`toolgate_runtime.graph_api` distribution; a new unpackaged
`packages/contracts/runtime_api.py` would not be an installable author SDK.
Canonical wire shapes remain in the existing generated contract schemas. Typed Runtime.context adapters and pinned
JSON graph selection remain useful now; token-in-config examples do not.

## 1. Intended outcome

Run one generic LangGraph worker image that loads the graph selected by a
published workflow profile at runtime. Authors configure workflows and agents
through JSON editors in their corresponding UI pages. Native Python packages
define graph behavior through LangGraph's supported APIs.

Changing prompts, models, tool references, trigger mappings, graph parameters
or a graph package selection must not require rebuilding the worker image when
the package's dependencies are already supported by that image. A dependency
outside the qualified runtime requires a qualified runtime/extension release;
do not silently install Python packages from the network during a run.

All execution continues through ToolGate admission, current authorization,
governed model/tool access, run ownership and confirmed result recording.

## 2. Current implementation and the gap

- `apps/langgraph-runtime/src/toolgate_runtime/runner.py` currently builds a
  fixed `advance` loop, optionally executing configured `agentStages`.
- Profiles and workflow/agent configuration already use JSON, with immutable
  staging, review and publication. The management UI primarily uses forms.
- The existing trigger-to-run path and generic WorkflowStack are foundations
  to reuse, not evidence that arbitrary native graphs already run safely.
- Native package loading, graph configuration contracts, persistent graph
  recovery, the complete JSON editor experience and their acceptance are new work.

## 3. Ownership and deployable unit

The deployable unit is a workflow bundle, selected through a profile:

| Owned item | Contents |
|---|---|
| Profile | Workflow catalog and explicitly declared deployment defaults |
| Workflow | Graph selection, configuration, agents, model definitions/references, trigger bindings, schemas, limits, monitoring and result delivery |
| Agent | Prompt, model selection, context, tool references, output schema and optional graph/subgraph selection |
| Graph package | Native Python code, entry points, schemas, dependency requirements, capability manifest, examples and README |
| Acceptance fixtures | Sample inputs, expected results and deterministic mock providers/tools |
| Private deployment state | Credentials, installed data, checkpoints and run results; excluded from ordinary bundle export |

Workflow and agent editors edit scoped sections of one authoritative profile
draft. Export may arrange those sections into folders, but must not introduce
independently editable copies that drift. Workflow deletion/export/replacement
must include its owned configuration and validate remaining references.

Environment variables contain deployment defaults and bootstrap references.
They must not override a published workflow's models, prompts or graph logic
implicitly. Credential values remain in private custody; JSON holds references.

Shared templates may be copied or explicitly version-pinned. Mutable global
agent/model configuration must not silently change a published workflow.

## 4. Graph package and runtime contract

Define canonical cross-component contracts under `packages/contracts/` before
adding application-specific representations. Regenerate all affected bindings.
Create an ADR before changing artifact formats, trust or persistence boundaries.

Each graph package declares:

- Package identity, immutable version and verified content digest.
- Named graph/factory entry points and accepted factory signatures.
- Exact supported runtime API and dependency requirements.
- JSON Schemas for graph configuration, input and output.
- Required capabilities, including streaming, persistent state, interrupts,
  subgraphs and asynchronous execution.
- A README, example configuration, sample inputs and executable acceptance tests.

Workflow JSON selects a package, digest and named entry point, then supplies
schema-validated configuration. Do not allow arbitrary filesystem/import paths
from a run request, Python source hidden in JSON strings or runtime `eval`.

Use the existing artifact verification mechanisms where they fit. Do not assume
that qualifying a tool archive automatically qualifies executable graph code.
Dependency resolution and package installation happen through a reviewed
provisioning path before a workflow becomes ready.

## 5. Native graph execution

The worker claims an authorized run and obtains its exact published profile,
graph digest, configuration and input snapshot. It verifies compatibility,
loads the declared entry point and invokes the native LangGraph object.

Graph factories receive only a validated, bounded snapshot of static JSON
configuration, never run-scoped clients, credentials, tokens or checkpoint
savers. Define deep immutability/copy semantics in the authoring contract:
`dict[str, Any]` is not immutable merely because its containing dataclass is
frozen. Static configuration may be captured; run-scoped dependencies may not.
Python factories can still use module globals, so this interface alone cannot
prove closure purity or make arbitrary packages safe for cross-tenant caching.

Resolve governed model/tool/agent capabilities per invocation through native
LangGraph `Runtime.context`, behind a small versioned ToolGate authoring API.
Keep raw lease tokens, checkpoint savers and backend credentials out of that
author-visible context. Live adapters must never enter graph state, durable
configuration, checkpoints, debug representations or tracing payloads. Avoid
using `configurable.run_ctx` as the public adapter container; reserve persistent
configuration identifiers for worker-controlled values. Native `RunnableConfig`
remains available for supported execution configuration, not as authority.

The typed author API exposes `runtime.context.models.invoke(...)`,
`runtime.context.tools.call(...)` and `runtime.context.agents.invoke(...)`.
Authors select declared agent/tool references and supply inputs; the trusted
boundary supplies run/lease scope, node occurrence, operation identity and
argument digests. Agent invocation must use current delegation authorization,
not a direct ungoverned subgraph call. Node-local bookkeeping is isolated across
concurrent tasks and must not be a shared mutable counter on the context object.

For the initial StateGraph factory contract, return an unbound builder and let
the worker compile a fresh per-run graph with the fenced saver through the
supported `compile(checkpointer=...)` API. Do not mutate a shared compiled graph
or treat `.with_config({"checkpointer": saver})` as attaching a saver. Additional
native entry-point forms need their own qualified binding contract before they
are advertised. Use LangGraph APIs for routing, concurrency, reducers, subgraphs,
streaming and interrupts rather than a separate JSON execution engine.

Cache verified package/schema/configuration artifacts by tenant/profile scope,
package digest, canonical static-configuration digest and runtime/dependency
identity. A numeric configuration revision alone is not a globally unique
cache key. Cross-tenant compiled-object reuse is deferred until closure,
module-global, saver and concurrent-run isolation have executed evidence.
Never cache live per-run adapters, tokens, state or grants in shared artifacts.

Every run pins graph, agent and configuration revisions. Editing/publishing a
draft cannot change an active run. Unsupported packages and obsolete contracts
are refused intact; no compatibility aliases, migrations or conversion paths.

## 6. Executable-code boundary

Native Python is executable code, not ordinary configuration. Package review,
provisioning and execution must reflect that distinction.

- Keep graph execution separate from privileged claim/provisioning credentials.
- Specify and test process/container isolation, filesystem access, resource
  bounds and network access before loading tenant-provided code.
- Keep provider secrets and Control database credentials out of graph code.
- Protected model/tool effects use ToolGate adapters and current authorization.
- Do not claim that restricted imports, AST checks or a Python virtual
  environment provide a security sandbox.
- Record code provenance and approved digest with the run.

Reuse #230 security interfaces and verification where applicable. Any new #230
implementation must follow its own stage workflow and authoritative progress
requirements; this plan grants no completed security stage.

## 7. UI authoring and publication

Provide corresponding editors for:

| Page | Editing scope |
|---|---|
| Profiles | Complete profile JSON, import/export and dependency overview |
| Workflows | Graph package/version/entry point, graph configuration, agents, schemas, limits, triggers, outputs and monitoring |
| Agents | Prompt, model, context, tool references, schemas and optional native subgraph configuration |
| Graph packages | Upload/provision reviewed packages; inspect manifest, source, schemas, examples and compatibility |
| Runs | Inputs, actual graph, node/agent progress, approval state, errors, traces and confirmed output |

The JSON editor needs highlighting, schema completion, formatting, inline
errors with JSON pointers, revision comparison and accessible keyboard controls.
Forms use the same schema and document as the JSON editor. Invalid text remains
an unsaved editor buffer and cannot corrupt the shared draft. Scoped edits must
detect conflicts rather than overwriting changes from another page/session.

Provide a read-only graph preview from the selected native graph where possible.
Dynamic routes must be labeled as dynamic; do not present a static preview as
proof of every possible runtime path. Python package changes are code releases,
not an unreviewed side effect of editing configuration.

Retain stage -> review -> provision -> publish. Separate structural validation,
dependency readiness, mock acceptance and live acceptance in the UI. A valid
schema or successful import is not a successful runnable deployment.

## 8. State, approvals and effect recovery

Persistent graph checkpoints must be tenant/run/revision scoped, bounded and
protected through the existing authority boundary. No graph gets direct Control
database credentials. Specify supported serialized values and refuse executable
or unsupported checkpoint content.

The saver/broker derives an unambiguous storage scope from trusted tenant,
profile and run identity, and binds the pinned workflow/graph/configuration
revision. Do not trust caller-supplied prefixes or concatenate unrestricted IDs
with ambiguous delimiters. Enforce the scope and current lease fence on every
read, write, pending write, history/list and deletion operation, including async
paths. Preserve LangGraph's subordinate checkpoint namespaces for nested graphs
inside that protected scope; do not overwrite them with one constant prefix.
Graph-supplied thread/namespace values cannot select another storage owner.

Application state, node updates, inputs, outputs and interrupt payloads are
bounded strict JSON values, checked before accepting node updates/checkpoints.
Disable pickle fallback and arbitrary object reconstruction. That setting alone
does not make `JsonPlusSerializer` JSON-only: it also supports MessagePack and
non-JSON Python types. Qualify the pinned serializer's allowlists and explicitly
defined internal checkpoint metadata separately, so native interrupts/subgraphs
remain usable without admitting arbitrary application objects. Message objects
and other SDK values need an explicit supported JSON projection; no silent
`str()` conversion or unreviewed codec expansion.

Use lease fencing and compare-and-set transitions for claims, approvals,
cancellation, pause and resume. A replacement worker may resume only the pinned
graph revision and a confirmed checkpoint under current authority.

Retries distinguish pure computation/read operations from effects. A LangGraph
node restart must not automatically repeat an uncertain file move, external
write or ticket update. Preserve ToolGate idempotency and outcome-unknown
semantics. Approval must bind the actual action/input and revision being resumed.

The trusted execution/adapter boundary derives each effect operation identity
from run scope, stable logical node/task occurrence (including loop/subgraph/
fan-out identity) and a replay-stable call identity. Attempt counts, worker IDs
and lease tokens must not change that identity on retry or failover. A graph's
optional operation label is not a trusted idempotency key. Validate uniqueness
and argument digests; conflicting reuse is refused. Effect classification comes
from current tool metadata/policy, not an author-supplied `effect_type="read"`.
The server ledger records reservations and confirmed/unknown outcomes. A key
alone does not provide exactly-once external writes; uncertain outcomes require
the existing recovery/reconciliation path, not automatic re-execution.

Multiple calls within a node use an explicit logical call slot. A node-entry
sequence index resets on replay and is valid only with replay-deterministic
call ordering. Bind each slot's ledger reservation to the resolved tool reference
and canonical-argument digest. That digest validates the existing identity; it
must not create a new identity when replayed arguments change. Matching confirmed
calls may return recorded results after current authorization; conflicting tool/
arguments or unknown outcomes stop for recovery without another effect.
Record/reuse non-deterministic decisions through durable task results before
effect selection. A counter plus an argument hash alone cannot make arbitrary
reordered or concurrent calls replay-safe; require durable logical operation
identities or refuse automatic replay in those cases.

On native interrupt, the worker confirms checkpoint persistence and atomically
records the pending approval before releasing its execution resources. The
process need not exit. Approval binds tool/action, exact target and full argument
digest, not just a preview or a graph-supplied `approved` boolean. Resume checks
current authority and may restart the interrupted node from its beginning;
operations before the interrupt therefore require the same replay protections.

## 9. Triggers, monitoring and deployment

Reuse #274 trigger package interfaces. Trigger bindings select a published
workflow and map the event into declared inputs. Manual/API execution enters
the same admission path. Graph packages do not add watch-specific Gateway or
client protocols, public listeners or Compose services.

Keep the generic stack under `deploy/compose/WorkflowStack`. The selected
profile supplies workflow configuration and pinned graph package references.
Provisioning resolves dependencies before startup/readiness. Each supplied use
case includes a README with startup, sample input/trigger, credentials setup,
expected output and verification instructions.

Workflow JSON configures supported Langfuse, LangSmith, OpenTelemetry and other
monitoring adapters by explicit capability and credential references. Define
content redaction, opt-in payload capture, retention and tenant isolation. Show
run/revision/node correlation and actual backend registration failures. Do not
claim an integration exists merely because its settings can be stored.

## 10. Target work breakdown within the two phases

| Step | Deliverable | Exit evidence |
|---|---|---|
| 1 | ADR, contracts, capability catalog and baseline amendment | Agreed ownership/trust model, generated binding checks and obsolete-format negatives |
| 2 | Graph package provisioning and readiness | Digest/dependency/entry-point/schema checks; cross-tenant and malformed-package negatives |
| 3 | Generic native graph loader and governed adapters | Native graph executes from a pinned profile; real denied effects and revision-isolation tests |
| 4 | Durable checkpoint, approval, cancellation and recovery integration | Restart/replica/race tests; no repeated uncertain effects; current authority enforced |
| 5 | Shared JSON editors, forms, package inspector and graph preview | UI/browser/accessibility tests, scoped edit conflicts and full publish lifecycle |
| 6 | Trigger, monitoring and generic-stack integration | Trigger-to-configured-graph result; manual path parity; trace and credential isolation |
| 7 | Example bundles and parallel acceptance matrix | Every declared supported capability has executed tests and retained evidence |
| 8 | Fresh-install/image/release qualification | Published generic image executes different profiles and packages without rebuilding for ordinary configuration changes |

No partial step is described as complete. Update
`docs/architecture/workflow-v1-status.md` with implementation, executed checks,
tested revision/digest, limitations and next work in the same commit series.
Intermediate PRs target `feature/workflow-enhancement` and remain draft until
their applicable acceptance is met. Preserve #230/#274 component gates.

### Baseline amendment

This feature extends #295 beyond its current fixed runner. Record the agreed
scope and phase allocation explicitly under Baseline changes before feature
implementation. Existing #295 earned progress remains 0/100; #274 remains 27%.
This plan allocates no new weight, changes no denominator and awards no credit.

## 11. Full target acceptance matrix (advanced rows are Phase 2)

All rows below are required acceptance work, not delivered capabilities. Test
only the exact LangGraph/runtime versions advertised by the capability manifest.
“Full SDK flexibility” means native graph authoring with a declared dependency
set, not an unsupported promise covering every future library/plugin feature.

| Capability | Required example/evidence |
|---|---|
| JSON/profile injection | Two profiles change prompts, model selection and graph configuration with the same worker image |
| Native graph APIs | StateGraph and each additionally advertised factory/functional/Pregel entry-point form |
| Agent/model execution | Configured agents use scoped models, tools, prompts, context and validated outputs |
| Branching and bounded loops | Input-dependent paths, termination, state reducers and enforced limits |
| Parallel work and joins | Concurrent branches, deterministic merge and conflicting-state negatives |
| Dynamic fan-out/map-reduce | Variable task count with bounded concurrency, ordered/correct aggregation and failure behavior |
| Subgraphs | Workflow and agent subgraphs with explicit configuration/state boundaries |
| Async and streaming | Async node execution, bounded event streaming, disconnect and cancellation behavior |
| Retry/error/timeout | Declared retry policies, error routes and time limits without replaying uncertain effects |
| Human review | Pause, approved/rejected resume, stale/duplicate approval and competing-reviewer races |
| Durable recovery | Worker restart and replacement replica resume with pinned revisions and fenced old workers |
| Memory/state | Declared checkpoint/store lifetimes, limits and tenant/thread isolation |
| Tool governance | Actual allow/deny/revocation tests for reads and writes; credentials never enter graph configuration |
| Trigger parity | Manual/API and supported trigger packages admit the same configured graph |
| Monitoring | Advertised integrations receive correlated, appropriately redacted data; outages are visible |
| UI lifecycle | Create/edit/import/export/review/provision/publish/test/debug using the corresponding pages |
| Package compatibility | Unsupported dependencies, wire formats and changed digests refused without conversion |
| Packaging/deployment | Fresh bundle installation, profile switching, cleanup, private credentials and per-use-case README |
| Per-run dependency isolation | Concurrent tenants/profiles with colliding numeric revisions cannot share adapters, credentials, state or cached tenant configuration |
| Checkpoint boundary | Forged scopes, stale leases, history/list/deletion escapes and nested subgraph namespace collisions are refused on sync and async paths |
| Effect replay identity | Crash after a write, two identical-tool calls in one node, calls on both sides of an interrupt, changed/reordered replay decisions, loop iterations and parallel fan-out preserve or distinguish operation identities correctly; argument conflicts and unknown effects cannot trigger duplicate writes |
| Strict JSON boundary | Non-JSON application values and arbitrary constructor payloads are refused; no pickle path; supported interrupt/subgraph checkpoint metadata still round-trips |

Provider and package acceptance uses deterministic mocks and parallel CI jobs.
Mocks do not replace the final live stack/model/tool acceptance where required.
Each advertised capability must be marked supported, unsupported or pending in
the runtime manifest and UI; an unsupported feature cannot silently fall back.

## 12. Phase 1 end-to-end acceptance

1. Build the current generic Quickstart/runtime images once. Select the fresh
   `native-log` profile using the existing numbered start menu or CLI.
2. Its log-watch package produces the configured event. The worker loads the
   independently installed native summary graph and invokes `log-diagnoser`.
   Verify noise suppression and compare the model summary with Control's result.
3. Stop that selected stack without deleting evidence. Select `native-hotfolder`.
4. Drop a text issue in its documented inbox. The installed supervisor graph
   invokes the configured issue agent, reads through governed tools and records
   an independently confirmed result and output file.
5. Confirm both workers used the same runtime image ID, while their JSON profiles
   selected different graph packages and trigger packages.
6. Exercise the basic profile/workflow/agent JSON text boxes, invalid buffers,
   staging/version repinning and dispatch updates through UI/component/browser
   tests. Actual deployment profiles are published through the existing Control
   APIs by the stack provisioner. Do not claim mock browser transport is a live
   server publication test.
7. Runtime prompt/model/graph configuration is resolved from the published lease.
   Changing physical deployment resources (images, folders, installed packages,
   credentials or local delivery bindings) still requires operator provisioning;
   publication alone is not a Docker orchestration service.

Executed run IDs, image IDs, checks and limitations belong in the authoritative
status document. The optional two-agent summary/archive use case remains an
existing supported supervisor profile; advanced approval/restart/replay and
untrusted-package acceptance belong to Phase 2.

## 13. References

- [Existing runtime architecture](docs/architecture/langgraph-runtime-plan.md)
- [Authoritative #295 status](docs/architecture/workflow-v1-status.md)
- [Trigger architecture](docs/architecture/device-triggers-plan.md)
- [Authoritative #274 status](docs/architecture/triggers-implementation-status.md)
- [Security invariants](docs/security/security-invariants.md)
- [Native LangGraph graph entry points](https://reference.langchain.com/python/langgraph-cli/schemas/Config/graphs)
- [LangGraph graph API](https://github.com/langchain-ai/docs/blob/main/src/oss/langgraph/graph-api.mdx)
- [LangGraph interrupts](https://github.com/langchain-ai/docs/blob/main/src/oss/langgraph/interrupts.mdx)
- [Pinned 1.2.20 StateGraph compilation](https://github.com/langchain-ai/langgraph/blob/1.2.20/libs/langgraph/langgraph/graph/state.py)
- [Pinned 1.2.20 graph configuration and saver resolution](https://github.com/langchain-ai/langgraph/blob/1.2.20/libs/langgraph/langgraph/pregel/main.py)
- [Native per-run context](https://reference.langchain.com/python/langgraph/runtime/Runtime)
- [Serializer capabilities and restrictions](https://reference.langchain.com/python/langgraph.checkpoint/serde/jsonplus/JsonPlusSerializer)

Native entry-point/graph/interrupt references were checked during planning on
2026-11-16. Their presence is not implementation or acceptance evidence.

The supplied runtime-contract feedback was reviewed on 2026-11-17. Only the
factory/runtime boundary, cache isolation, checkpoint/effect recovery rules and
their acceptance cases were refined. The pasted Python API remains a proposal;
no `runtime_api.py`, runtime code or new trust guarantee is delivered here.
