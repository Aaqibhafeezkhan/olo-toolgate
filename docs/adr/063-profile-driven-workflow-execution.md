<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 063: Profile-driven external workflow execution

**Status:** Proposed for #295 V1.0; acceptance requires review and integration.

## Context and inspected baseline

Inspected source: `54a9c21cc538fdfd90b30f5fcefa7560c209a92d`.
[The v1 plan](../architecture/langgraph-runtime-plan.md#15-delivery-phases--minimal-end-to-end-v1)
and [live status](../architecture/workflow-v1-status.md) define release scope.
The following are actual reuse points, not evidence of workflow support:

| Existing source | Observed behavior | Required #295 delta |
|---|---|---|
| `packages/contracts/schemas/v2/profile.schema.json` | DeploymentProfile version 1 has packages, package trigger assignments and readiness cases; unknown fields rejected | Fresh canonical version 2 profile adds workflow configuration; no v1 conversion path |
| `application/PackageProvisioning.java` | Package verification, targets, bindings and client trigger assignment application | Orchestrate this service with catalog provisioning; do not duplicate installation |
| `domain/Ids.java` and `application/DirectoryService.java` | Existing agents/groups, roles/grants and bindings; no workflow/model directory kinds | Workflow/group and model/group catalog with registered management actions |
| `application/TriggerSubscriptions.java` | Durable subscription owner is `user:<caller>`, stored as ownerUserId; per-source trigger.subscribe checks | Keep listener user ownership; separate approved workflow service-run authority from this owner |
| `adapter/TriggerResource.java` and `adapter/TriggerStreamSocket.java` | Subscription REST endpoints and ticket-authenticated `toolgate.triggers.v1` stream | Reuse existing protocol and caller authentication; add only workflow admission adapter |
| `application/TriggerStream.java` | Durable subscription/cursor delivery, cumulative ack, current grant rechecks, explicit retention gap; watch is ephemeral | Require durable subscription; acknowledge only contiguous durably admitted/rejected deliveries |
| `application/EnterpriseEvaluator.java` | Existing permission evaluation | New workflow/model actions preserve complete tuples and current authorization |

Paths under application/adapter/domain are relative to
`apps/control-plane/src/main/java/io/ololabs/toolgate/control/`.
No workflow run ledger, generic LangGraph application or model-proxy endpoint is
claimed by these reuse points. Their absence was checked in the source inventory.

## Contract inventory after milestone #297

| Boundary | Current contract | Remaining server work |
|---|---|---|
| Profile configuration | DeploymentProfile v2 and the workflow-profile lifecycle operations are published in Control OpenAPI. Profile and workflow version IDs are distinct from the canonical profile digest. | Apply the complete reviewed configuration and collect readiness evidence. |
| Message submission | The proposed workflow run endpoint derives caller identity and pins the published revision. | Define the request, status and idempotency contract together with the durable Control implementation. |
| Trigger submission | WorkflowDispatchRequest and WorkflowDispatchReceipt are canonical v2 schemas. The listener sends only subscription UID and cursor. Optional receipt fields are omitted when absent. | Implement and publish the Control endpoint, including durable replay and terminal refusal semantics. |
| Runner ownership and steps | The standalone graph uses a runtime interface and a simulated adapter in tests. | Define and implement workload-authenticated claim, expiry, step and completion operations without exposing provider credentials. |

An ADMITTED receipt must contain a run ID. REJECTED and FILTERED receipts
must omit it. DUPLICATE retains the original admission ID and includes a run
ID only when the original outcome had one. These disposition rules are semantic
checks in addition to the structural schemas.

## Decision: canonical fresh profile and publication

Use DeploymentProfile version 2 as the sole fresh use-case profile contract for
this feature. It retains package declarations and adds catalog configuration,
workflow bindings and readiness scenarios. No second installer or parallel
use-case bundle format is introduced. V1.1 changes the schema and generated
bindings together; unsupported stored/profile versions are rejected with an
explicit error and retained for operator inspection, never converted/deleted.

Tenant comes from the authenticated request. A profile cannot declare a foreign
tenant or secret value. Collections have bounded sizes, unique typed IDs, closed
object shapes and reference closure. Workflow definitions declare group,
supervisor prompt, explicit modelGroupId, bounded context declarations, input
and output schemas, agent-group narrowing and finite execution limits. Agent
definitions independently declare prompt/context/output schema and modelGroupId.
Model deployment credentials are vault references; package content stays pinned.
Resource references are either owned declarations or explicitly reviewed shared
references; imports cannot overwrite another profile's owned resource.

Validate the complete desired graph and management authority before applying
changes. Store a canonical digest and immutable revision. Stage catalog records
unavailable to runs, provision packages through PackageProvisioning, then mark
the revision READY only after dependencies and readiness checks succeed. Atomic
publication pointer activation exposes the revision; asynchronous provisioning
is not represented as a database transaction spanning downloads. Failures retain
a visible FAILED/PENDING report; retries reconcile the same desired revision.
Reapply identical content idempotently; omission never deletes shared resources.

## Decision: listener registration and dispatch admission

V1 uses an authenticated human/operator-owned durable subscription with an
explicit source list. The standalone process obtains a one-use stream ticket,
connects to `/api/control/v1/triggers/stream`, selects `toolgate.triggers.v1`,
subscribes and obeys credit/cursor rules. It does not impersonate a service agent
on today's user-only subscription API. Stream credentials remain sensitive
bootstrap credentials, never profile content or logged configuration.

A published profile binding identifies the subscription UID (not merely its
reusable name), source assignment UID, workflow/version and service Agent/group.
Creating it requires workflow management plus source subscription authority and
review of service delegation; the service Agent must independently hold workflow,
agent-delegation, model and tool authority. The listener owner merely transports
events. It gains no service token and cannot supply runAs. Before delivery can
start a run, Control rechecks listener/source read authority and service execution
authority. Expired/recreated subscriptions cannot inherit an old binding.

`POST /api/control/v1/workflow-dispatches:admit` accepts
only subscription UID and opaque delivered cursor, with ordinary authenticated
caller credentials. Control verifies caller ownership, that this cursor was
delivered to that subscription, current source/binding status and full data
clearance for configured input fields. It loads event data from its own log;
caller-provided event text, identities, workflow IDs/versions or prompts are
rejected. Redacted event fields must not be rehydrated into a more privileged
workflow without authorized source clearance.

Within one tenant transaction, persist the resolved binding revision, workflow
version/profile digest, service delegation and run, and unique admission key
`(tenant, subscription UID, cursor)`. A retry returns the existing outcome and
never changes the binding/version or creates another run. Payload mapping is
bounded JSON pointers/constants with explicit message template; no code/eval.
Record rejected/filtered outcomes durably, without granting execution. Auth
failure, unavailable authority or uncommitted transient error never earns an ack.

The listener acknowledges only after receiving a durable admission or terminal
rejection reference. Because ack is cumulative, process serially in v1 and never
ack beyond an unresolved earlier cursor. Crash after admission/before ack yields
the same admission on redelivery. Retention gaps require explicit failed/operator
recovery status; do not pretend missing events were handled. A known replay may
return its prior reference after disablement, but cannot execute a new effect;
run authority is rechecked separately. Re-enabling a binding cannot resurrect
previous terminal rejected events automatically.

The trigger admission gate checks current Actor Service role grants on the
configured service agent group. Use explicit `workflow.execute` and
`model.infer` actions with exact `CUSTOM` resource selectors
`workflow-group:<id>` and `model-group:<id>` respectively. The admission check
requires unrestricted tool and device selectors because it has no tool or
device target. Time conditions are evaluated using Control's clock. Runtime
model and tool calls require their own current authorization.

The initial one-agent slice binds the trigger's service Agent to the same
entry-eligible agent and group declared on the workflow. Control authorizes
the workflow supervisor and that agent through the lease-bound step endpoint.
Issuing a new step replaces the previous token, and closing the run clears
the token. Cross-agent delegation requires a separate runtime authorization
path before it can be enabled.

## Decision: message admission, runner identity and execution

`POST /api/control/v1/workflows/{id}/runs` accepts message, optional thread
reference and an idempotency key under authenticated user credentials. It derives
tenant/user, validates workflow execute permission and model access, pins a READY
revision, and creates the same run type as trigger admission. User input never
sets execution identities. V1 does not promise durable conversation continuation.

A separate workload-authenticated runtime claim endpoint returns a single active
ownership token scoped to tenant/run/expiry and pinned resolved configuration.
A listener can run beside the worker in the same Python program, but subscription
credentials and workload credentials have distinct authority. Claim does not
make a runner a tenant administrator. Handoff obtains an agent-step token with
current subject delegation, workflow, agent, model-group and tool restrictions;
models use the thin Gateway proxy and tools/context use governed Gateway paths.
Supervisor inference has its own workflow-step scope, not an invented Agent ID.
No provider endpoints, credentials or unrestricted database queries reach Python.

V1 run states: QUEUED -> RUNNING -> SUCCEEDED | FAILED | AWAITING_APPROVAL.
QUEUED/RUNNING may become CANCELLED or EXPIRED. Terminal outcomes are immutable.
AWAITING_APPROVAL is non-executing and has finite expiry; durable resume is later
scope. Single-owner conditional writes and expiry checks prevent stale/duplicate
workers from completing or executing steps. After lost ownership v1 fails the
run visibly rather than automatically repeating uncertain work. Existing effect
identity, consent and reconciliation govern tool calls; never retry unknown
mutations based solely on a failed Python call. Persist minimal redacted status,
not plaintext checkpoints or arbitrary transcripts. Encryption requirements apply
to any retained sensitive content.

Discovery is filtered by workflow eligibility AND current user/service tuples;
each agent receives its own governed tools. Pinned definitions do not cache
permissions. Every protected call checks current authority and kill switches.
PREPARE has no mutation authority; APPLY still requires existing ALLOW/ASK/BLOCK
and effect protections. Host execution cannot certify arbitrary host egress
confinement; credentials and endpoints remain protected by ToolGate enforcement.

## Delivery and verification

The first lifecycle implementation uses tenant-owned immutable revision rows,
separate compare-and-swap publication heads and permanent resource ownership.
Private desired documents use the existing tenant vault key custody and AES-GCM
envelopes, with 32-KiB chunks bounded by the 256-KiB profile ceiling. Associated
data binds tenant, profile, canonical digest, key version, chunk index and count.
Ciphertext rows reference the tenant key version; normal rotation retains the
old READ_ONLY key. Unknown formats, unavailable/retired keys, modified or reordered
chunks and cross-tenant use fail closed. No plaintext or legacy document reader
is provided. Publication metadata and ownership IDs remain ordinary tenant rows.
Canonical profile version 2 carries optional `workflows`; package-only profiles
use the same current format. Explicit model deployments contain TLS origin,
model ID and vault name/group references, never secret values. Initial lifecycle
management is restricted to current Super Admins, with explicit self-review
allowed; scoped workflow management roles and independent review quorums are
not claimed. Package provisioning is a separate operation on reviewed bytes,
and activation requires its exact digest's READY outcome. Tool readiness cases
without integrated execution evidence block activation. This establishes the
staging boundary, not complete profile provisioning or runtime authority.

V1.1: profile schema/generated contracts, ownership and staged publication,
workflow/group/model catalog and effective resolution. V1.2: minimal run ledger,
workload claim/handoff, model proxy and standalone runner/message example.
V1.3: admission adapter and generic durable listener; existing #274 code changes
must follow its applicable component/status requirements. V1.4: two profiles,
unchanged runner/dependency lock and real governed models/tools.

The machine-readable [acceptance scenarios](../architecture/workflow-v1-acceptance.json)
define arrange/action/expected outcomes for the future integration harness.
They are test specifications, not executed runtime tests. V1.0 remains pending
review and executable harness integration; no implementation credit is earned.
Quotas, model weights/budgets, multi-replica checkpoint recovery and container
publication remain later scope. Basic bounded inputs, ownership and current
authorization are required immediately.
