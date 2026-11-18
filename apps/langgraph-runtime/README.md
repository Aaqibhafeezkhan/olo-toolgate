<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Generic ToolGate runtime â€” execution and trigger foundations

Phase 1 adds required profile `graph` selection: packageId, version, canonical
manifest digest, named entryPoint and JSON config. The same declaration is pinned
in the claimed run. The worker loads a fresh native StateGraph from an
operator-installed immutable directory. Graph-less bot/leases are refused;
there is no fallback or migration. Existing `agentStages` belongs to the installed
supervisor package, not to the generic native graph loader.

Graph factories import `RunContext` from `toolgate_runtime.graph_api`, return an
uncompiled `StateGraph(..., context_schema=RunContext)` and receive only a private
static JSON snapshot. Native async nodes call `runtime.context.agents.invoke`,
`models.invoke` or `tools.call`. Current Control/Gateway authority is rechecked;
protected operations are serialized within a run for the current one-active-step
ledger. Unknown/denied outcomes latch and cannot be hidden by a graph exception
handler. Application state/results are bounded JSON. Package code is trusted
operator-installed Python, **not** sandboxed tenant code.

Use `python tools/workflows/graph_package.py <sources> <configuration>/graphs`
to install a directory and obtain its profile selection. Each package declares
exact installed dependency versions, source hashes, factory schemas and required
capabilities. No per-run pip installation occurs. The worker uses
`--graph-directory /configuration/graphs`. Preflight with
`python -m toolgate_runtime.graph_packages --profile <profile.json> --workflow <id>
--graph-directory <graphs>` before startup. This validates installed content and
configuration, not model credentials or a completed workflow.

The `native-hotfolder` and `native-log` stack configurations demonstrate two
trigger sources and different native packages on one image. The independent
[summary package](../../examples/native-graphs/summary/README.md) illustrates the
public author API. The existing UI has basic profile/workflow/agent JSON text
boxes: apply to the draft, stage, review and publish. Source/deployment changes
must use fresh stores for the new required contract.

Persistent checkpoints, native interrupt resume, automatic effect retry,
concurrent protected steps and untrusted-code upload are Phase 2. Packages that
request unsupported capabilities are refused before import. Additional native
factory forms must be explicitly qualified before use; this first version
requires an unbound StateGraph. A native graph must finish with a JSON `result`.

For the complete selectable local deployment, use
[`WorkflowStack`](../../deploy/compose/WorkflowStack/README.md). Its configurations include
issue triage and a two-agent text-summary/archive use case. Full roadmap
acceptance remains separate from these bounded local executions.

An optional pinned `agentStages` array selects fixed ordered execution instead
of model-directed supervisor routing. Each entry selects a declared agent and
`CONTEXT` or `MODEL` result mode. Context stages return only successful configured
context reads without inference; model stages run the configured agent loop.
Each stage rechecks current eligibility and step authority. The admitted message
feeds the first stage, completed results feed subsequent stages, and the last
result completes the workflow. Model responses cannot skip these stages. The
current lease wire format explicitly includes `agentStages` (empty for routing);
obsolete snapshots without that field are rejected.

An agent with configured context reads can return `{"kind":"finish_context"}`
to finish with those confirmed tool values and the admitted source path. The
supervisor can delegate using
`{"kind":"delegate","agentId":"target","inputFromAgent":"completed-source"}`.
The built-in supervisor copies that completed result within the same run after checking
candidate eligibility; foreign/unfinished references fail closed. No adapter
credential enters the transferred context. This permits full file handoff within
the inference response budget. Every tool/model request retains current server
authorization; references do not grant another agent's tools.

Gateway requests JSON-schema-constrained decisions using the canonical
`workflow-decision.schema.json` contract and independently validates the returned
JSON. The approved model backend must support the `json_schema` response format.
These syntax constraints do not authorize a delegation or tool call; current
profile eligibility and server grants still decide each step.

The built-in package runs the existing LangGraph supervisor/agent loop against a supplied
`ToolGate` adapter. Workflows, agent IDs, explicit models, prompts, context tools,
eligibility, remediation mode and execution limits come from a leased snapshot.
The built-in graph has no use-case IDs, provider keys, provider clients, arbitrary imports
or executable profile expressions. It does not union different agents' tools.

The worker uses the Control lease API and the Gateway model and MCP endpoints.
The trigger subscriber uses the existing stream and the Control admission API.
The first live profile qualification is tracked in the workflow v1 status.
Each worker result includes an `execution` summary of completed inference
responses and attempted tools, with subject IDs, tool IDs and outcome states.
It excludes prompts, arguments, model/tool results and credentials. The summary
is scoped to one run and bounded by its step limit. These are worker observations;
independent server evidence is still required for full qualification.

## JSON graphs and private rebuild API

The admin profile lifecycle now includes **Prepare reviewed graphs**. Control
fetches the exact reviewed document, asks its installation-selected runtime to
compile every graph, then records all receipts in one tenant transaction after
rechecking management authority and publication generation. Partial/failed builds
leave previous receipts intact. Package readiness alone cannot publish a profile.
The rebuild endpoint supports the installed JSON factory and explicitly selected,
verified native factories. An invalid JSON graph never falls back to a native
factory. Set the runtime API's installation-owned `graphDirectory` when additional
operator-installed graph packages are mounted, and mount the same packages on
workers. Native package code remains trusted operator code, not sandboxed uploads.

Configure Control with these installation-owned settings, outside profiles:

```text
TOOLGATE_CONTROL_RUNTIME_BUILDER_ORIGIN=https://runtime-builder:8447
TOOLGATE_CONTROL_RUNTIME_BUILDER_IDENTITY_PATH=/run/credentials/runtime/control-builder.p12
TOOLGATE_CONTROL_RUNTIME_BUILDER_PASSWORD_PATH=/run/credentials/runtime/identity.password
TOOLGATE_CONTROL_RUNTIME_BUILDER_CA_PATH=/run/credentials/runtime/runtime-ca.crt
```

Use an operator-issued Control service client certificate accepted by the runtime's
`clientNames` and `clientCa`, and a server certificate valid for `runtime-builder`.
Given those existing credentials, create the client identity without placing a
password on the command line:

```sh
openssl pkcs12 -export -in credentials/runtime/control-builder.crt \
  -inkey credentials/runtime/control-builder.key \
  -out credentials/runtime/control-builder.p12 \
  -passout file:credentials/runtime/identity.password
chmod 600 credentials/runtime/control-builder.p12 credentials/runtime/identity.password
```

The identity/password must be private, protected files readable by Control; mount
them read-only. Import does not create certificates, grants, mounts or firewall
rules. Runtime traffic remains on the private installation network; these settings
do not require opening an inbound host port.

Each admitted run now pins the build's compiler digest, covering the installed
loader, author API, JSON compiler and governed runner with normalized line endings.
Workers refuse a different compiler before executing JSON or native graph effects. Control continues a queued or
running admitted revision after a new publication, while rechecking live step,
lease, kill and model/tool authority. New admissions use the new publication.
Fresh schemas and the current lease shape are required; unsupported existing
databases/snapshots are refused without conversion.

The installed `olo.json-graph` factory now compiles topology from workflow JSON.
Get its verified selection metadata with:

```sh
python tools/workflows/json_graph_package.py --check
```

Set that selection's `config` to a graph such as:

```json
{
  "schemaVersion": 1,
  "nodes": [
    {"id": "summarize", "kind": "agent", "agentId": "your-configured-agent", "input": {"$path": "/message"}}
  ],
  "edges": [
    {"from": ["START"], "to": "summarize"},
    {"from": ["summarize"], "to": "END"}
  ],
  "output": {"$path": "/values/summarize"},
  "outputSchema": {"type": "object"}
}
```

`agentId` must be bound in the reviewed workflow. Nodes currently support
`value`, `agent`, `model`, `tool` and `branch`; edges support static parallel
execution, explicit all-predecessor joins and recursion-bounded loops. Expressions
support `$path`, `$literal`, `$eq`, `$lt`, `$and`, `$or` and numeric `$add`.
No Python expressions/import paths are evaluated from the profile. All actual
model/tool/agent operations still use the run-scoped governed adapters.

The shared compiler cache contains only pure topology and copied JSON, with no
run credentials or adapters. Cache eviction does not terminate a running graph.
Native installed factories retain their existing isolated loading semantics.
Subgraphs, dynamic fan-out and durable interrupt/resume are **not implemented by
this JSON compiler yet** and are refused, rather than silently ignored.

The new [private runtime API](../../packages/contracts/openapi/runtime-v1.yaml)
provides rebuild, actual capabilities and cache eviction. Start its service with
an installation-owned configuration file:

```sh
python -m toolgate_runtime.graph_server --configuration /run/credentials/runtime-api.json
```

The file declares `host`, `port`, server `certificate`/`key`, `clientCa`, allowed
Control certificate `clientNames`, `controlOrigin`, `controlCaFile` and a `readers`
map from tenant IDs to workload-token file paths. Paths and private credentials
are supplied by the operator, outside workflow bundles. There is no anonymous
or plain-HTTP mode. Use a dedicated service CA/client identity; do not publish
this internal API as an unauthenticated Internet endpoint.

`POST /internal/runtime/v1/graphs:rebuild` accepts the canonical candidate
reference (`tenantId`, `profileId`, `profileDigest`, `workflowId`,
`expectedGeneration`). The service fetches the exact reviewed graph from
Control's direct-TLS runtime-service endpoint and checks tenant, workflow,
generation and bound agents before compiling. Every call reauthorizes, including
cache hits. The receipt means **compiled**, not published, deployed or runnable;
the full provisioning/activation barrier and console integration are pending.
Model hosting remains external. This component does not create folders, change
permissions, open firewall ports or provision model services.

## Dynamic subscription listener

The listener can follow the current caller's authorized active subscriptions:

```sh
python -m toolgate_runtime.trigger_listener --control-origin https://control.example \
  --follow-subscriptions --token-file /run/credentials/listener.token \
  --ca-file /run/credentials/control-ca.crt
```

The token must have current subscription/admission authority. The listener reads
the complete bounded catalog, adds/removes sessions and drains a removed UID
before replacing the same subscription name. It never chooses workflows from
event data, generates acknowledgements without admission, or uses a local event
deduplication flag. Control's durable admission is the workflow consumer's
duplicate/in-progress boundary. `scheduled` statistics count listener loops,
not proof that every underlying stream is connected or a device trigger healthy.

## Install and verify outside containers

The Python CI group runs `python tools/workflows/check.py` in an isolated
environment using the pinned lock. From the repository root, use Python 3.11 or later:

```sh
python3 -m venv .dev/workflow-runtime
.dev/workflow-runtime/bin/python -m pip install -r apps/langgraph-runtime/requirements.lock
.dev/workflow-runtime/bin/python -m pip install --no-deps -e apps/langgraph-runtime
.dev/workflow-runtime/bin/python -m unittest discover -s apps/langgraph-runtime/tests -v
```

The lock pins the tested Python dependency versions; it is not a hash-verified
or cross-platform production dependency qualification. The tests execute real
LangGraph nodes with a simulated ToolGate boundary and two distinct use-case
configurations using unchanged runner code. They do not qualify real models,
tools, server authorization or workflow publication.

## Adapter contract

`Runner(adapter).execute(lease)` accepts a server-admitted `RunLease`, never a
caller-authored profile or self-selected identity. This internal Python snapshot
is not a new profile format or a published wire contract. The transport
must decode the canonical server contract, cap input/response bytes, verify TLS,
use configured infrastructure endpoints, and enforce deadlines without redirects,
credential logging or automatic retries. Prompts and results are sensitive.

The adapter implements `authorize_step`, `discover_agents`, `discover_tools`, `infer`,
`call_tool` and `finish`. ToolGate independently enforces current tenant,
run ownership, delegation, model/tool permissions, profile readiness and kill
switches at every protected operation. It must bind mutations to existing
single-use effect/consent identities and record authoritative outcomes. The
local scope/membership checks only narrow candidates; they never grant access.
The adapter must not invent fresh effect identities to retry uncertain work.

The supervisor receives its configured prompt/model and freshly discovered agent
summaries, narrowed by the pinned workflow eligibility filters. Other agents'
private prompts and model settings are not included in these summaries. It may return exactly:

```json
{"kind":"delegate","agentId":"configured-agent","input":"untrusted input"}
{"kind":"finish","result":{"answer":"workflow result"}}
```

An agent receives its own configured prompt/model, context and currently available
tools. It may return exactly `finish` as above or:

```json
{"kind":"tool","toolId":"package/export","arguments":{"key":"value"}}
```

All decisions and tool results are treated as untrusted bounded JSON. Unknown
agents, tools, extra model-selection fields and malformed decisions fail closed.
The Gateway requests a structured decision from the configured model and the
runner validates it before taking the next step.

## Stop and completion semantics

- Reauthorize each inference/context/tool step; discovery snapshots confer no
  future authority. Context retrieval is read-only even in APPLY mode.
- PREPARE refuses mutation locally; ToolGate must also enforce the mode. ASK
  stops at `AWAITING_APPROVAL`; durable approval resume is later scope.
- Denial, invalid scope, unknown outcome, runtime failure or step/handoff/time
  exhaustion stops execution without retries. An ambiguous tool transport error
  is `TOOL_OUTCOME_UNKNOWN`, not permission to resend.
- Ownership loss propagates immediately without a finish request under a stale
  lease. Completion is returned only after ToolGate confirms `finish` with an explicit
  matching run/lease/state acknowledgement.
  `CompletionUnconfirmed` never means the effects failed or are safe to repeat.
- The adapter gets a positive remaining timeout per protected call and a separate
  five-second budget to close the ledger. A synchronous adapter must honor it;
  the library cannot interrupt a hung foreign implementation.
- Credentials stay out of graph state and object representations. Internal model,
  tool and completion calls run with LangSmith tracing disabled, including when
  tracing was enabled by the host. This does not sandbox a hostile host or stop
  an application from explicitly logging its own inputs.

Durable workflow checkpoints, automatic workflow failover/replay, cancellation
propagation, and model streaming remain pending.
The [v1 qualification guide](../../docs/testing/workflow-v1-qualification.md)
describes the repeatable scenario controller, private evidence reports and
explicit blocked outcomes while live server integration is missing.
See the [live status](../../docs/architecture/workflow-v1-status.md) and
[architecture plan](../../docs/architecture/langgraph-runtime-plan.md).

## V1.3 trigger subscriber

The default image entrypoint runs `toolgate_runtime.service`: a supervised graph
API and execution worker. Trigger routing belongs to Gateway/backend; there is
no listener or identity issuer in the standard deployment. Process-local graph
caches are bounded and disposable. Durable state and run leases belong to Control.

Pass `--configuration /installation/service.json` with installation-owned JSON:

```json
{
  "graphConfiguration": "/installation/graph.json",
  "controlOrigin": "https://control.example",
  "gatewayOrigin": "https://gateway.example",
  "tokenFile": "/installation/worker.token",
  "caFile": "/installation/ca.crt"
}
```

The graph configuration retains the private mTLS contract described above. Mount
only this service's credential/configuration directory read-only. The service
writes no durable run state. Provision/replace credentials through external
operator tooling; Admin passwords and signing keys never belong in this image.
Required child failure stops siblings and exits nonzero. SIGTERM stops both;
container health probes loopback `/health/ready` on port 19461. This reports local
process/listener readiness, not permission to run any particular workflow.
Single-instance Quickstart uses local caches. Production may scale identical
instances behind a graph-API proxy with backend-issued exclusive run leases;
external cache and checkpoint/resume remain separate future work.

The image also retains explicit worker/listener module commands for protocol
testing; the standard three-container stack does not launch a runtime listener.
Release builds publish `olo-toolgate-langgraph-runtime:<release-version>` to
`ghcr.io/olo-labs` and the configured Docker Hub namespace
(`DOCKERHUB_NAMESPACE`, or `DOCKERHUB_USERNAME` when unset). For example, a
namespace of `ololab` produces
`docker.io/ololab/olo-toolgate-langgraph-runtime:<release-version>`.
Ordinary branch CI builds and verifies the image but does not publish it.
Publication requires the release workflow and all its required gates; adding
the image does not qualify the currently incomplete live workflow slice.
The image uses digest-pinned Python 3.12 on Alpine, applies available OS package
updates and installs the unchanged dependency lock. CI executes runtime tests
inside the image; TLS fixtures skip there when the OpenSSL CLI is unavailable,
and the foundation runtime gate exercises them on its OpenSSL-equipped host.

Build it and inspect both entry points from the repository root:

```sh
docker build -t toolgate-langgraph-runtime:local apps/langgraph-runtime
docker run --rm --read-only toolgate-langgraph-runtime:local --help
docker run --rm --read-only --entrypoint /opt/toolgate-runtime/bin/python \
  toolgate-langgraph-runtime:local -m toolgate_runtime.trigger_listener --help
```

The image runs as UID 65532. The listener uses a subscription owner credential
provided through the process environment. The worker takes a separate mounted
`--token-file` containing a short-lived `toolgate-workflow-runtime` credential
for the configured tenant, plus `--control-origin`, `--gateway-origin`, and the
respective CA files. Provide the worker with direct TLS access to Control's
internal runtime listener. The worker reloads the mounted credential on every
request. Configure `TOOLGATE_WORKFLOW_MODEL_ORIGINS` on the Gateway as a
comma-separated list of approved HTTPS origins. Model containers supply a
TLS-protected OpenAI-compatible completion endpoint trusted by the Gateway.
Set `TOOLGATE_WORKFLOW_MODEL_CA_FILE` to a mounted PEM trust bundle for model
endpoints using a private CA.

The generic listener reuses the current authenticated subscription/ticket APIs and
`toolgate.triggers.v1`. Obtain the subscription name and immutable UID from the
reviewed ToolGate configuration; supply the existing listener user's token through
an environment variable, never an event or command-line token value. The listener
does not create identities, grants or source assignments, and cannot select a
workflow/default version or service Agent from event text.

After installation outside containers:

```sh
.dev/workflow-runtime/bin/python -m toolgate_runtime.trigger_listener \
  --control-origin https://toolgate.example \
  --subscription subscription-from-profile \
  --subscription-uid ts-immutable-uid-from-toolgate \
  --ca-file /path/to/toolgate-ca.crt
```

Set `TOOLGATE_LISTENER_TOKEN` securely in that process's environment first. Use
`--max-events 1` for one durable admission outcome. Output contains admission/run
references and the disposition; it never claims the workflow completed. There is
no built-in use-case selection or provider credential.

Each connection rechecks the subscription's UID/Active state, obtains a fresh
single-use ticket and selects only `toolgate.triggers.v1`. HTTPS and WSS verify
certificates, disable ambient proxies and refuse redirects; the private socket
logger cannot print ticket headers. Only the server's persisted cursor is used
on reconnect; no local `resumeAfter` or ephemeral watch can skip work.

The new canonical `WorkflowDispatchRequest` sends only `subscriptionUid` and
`cursor` to `POST /api/control/v1/workflow-dispatches:admit` (ADR 063). The response
is a matching `WorkflowDispatchReceipt`: durable admission ID, disposition and,
where admitted, run ID. Control must look up its own event, resolve the published
default-workflow binding and current service grants, and atomically store/dedupe
the run/outcome by tenant/subscription UID/cursor. ADMITTED requires a run ID;
DUPLICATE refers to the original durable outcome and may have no run for an
original rejection/filter. The listener never substitutes its event payload for
that authoritative lookup.

Control now implements the admission endpoint. A server's 404/401/503,
a timeout, or any non-durable/mismatched receipt stops the connection
without ACK. There is no local admission ledger, fake production success or
fallback workflow. Registration plus an uploaded profile is not enough for
execution: the profile must be reviewed, dependency-ready and published, and the
workload worker and approved model transport must be running. Admitted work belongs to Control's run queue;
the listener never fabricates a RunLease or executes an event as Python code.

Processing is serial: commit evidence first, ACK second, credit third. Any error
permanently stops that connection's bridge, preventing a cumulative ACK for a
later cursor from skipping unresolved work. A lost ACK may cause redelivery;
Control must return the original admission/run, not create another effect. Gaps,
revocation and protocol errors stop visibly. Restarting/reconnecting revalidates
UID and uses a new ticket; no automatic retry of workflow effects is added.

The runtime gate includes local ephemeral-certificate HTTPS/WSS tests and a
simulated admission ledger. These prove client protocol/order/failure behavior,
not real Control persistence, authentication/delegation enforcement, exactly-once
business effects or real trigger-to-workflow completion. TLS tests require
OpenSSL and explicitly skip where it is unavailable.
The image CI gate uses the Dockerfile's `verification` stage with OpenSSL and
runs the installed-runtime tests with read-only fixtures. The final `production`
stage excludes that test-only executable.

Workers advertise the current compiler digest and verified installed graph-package
digests in every `POST /internal/v1/runtime/claim` body. Control leaves incompatible
runs queued. The bounded inventory verifies package bytes without importing graph
factories; `--graph-directory` adds operator-installed packages to the built-in
inventory. Empty inventories claim no work. A worker checks the returned lease
against its advertised identities before execution.

Both claim and `POST /internal/v1/runtime/workers:heartbeat` use canonical
`WorkflowWorkerCapabilities`: `instanceId`, `compilerDigest`, and `graphDigests`.
Each process creates one instance ID and retains it across credential refresh.
Control records tenant/owner-bound presence for 30 seconds, with at most 256
unexpired instances per tenant. A background heartbeat renews every five seconds
while a workflow is busy, without claiming another run. Failure stops subsequent
claims; current run leases and step authorization remain independent. A crashed
or stopped process disappears from readiness at expiry, even without a sweep.

Publication and new admission require recent compatible worker presence for each
prepared graph. This is bounded capability/liveness evidence, not remote code
attestation, proof of instantaneous health, per-replica graph compilation or
checkpoint/crash recovery. Other deployment and provider readiness still apply.
