<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ToolGate LangGraph workflow execution plan

Status: Phase one implementation in progress.

## Phase one goal

Deliver one real ToolGate trigger invoking one published workflow and one
configured agent in the dedicated LangGraph container. Use the production
admission contract, profile publication, runtime identity, and Gateway execution
paths from [ADR 063](../adr/063-profile-driven-workflow-execution.md). The trigger
binding comes from the published profile. The listener supplies the immutable
subscription UID and cursor, and Control resolves the stored event and selected
workflow. Persist the pinned admission before acknowledging the delivery.

| Increment | Acceptance |
| --- | --- |
| Resolve a published dispatch | Resolve the current reviewed binding by subscription UID and assignment UID. Deny missing, withdrawn, or unready bindings. |
| Admit one trigger durably | Authenticate the listener, verify the stored delivery and source access, and atomically persist the admission and queued run. Redelivery returns the original receipt. |
| Execute one configured agent | The worker claims the admitted run, receives the pinned configuration through ToolGate, uses one approved external model, and invokes any tool through Gateway with current authorization. Report the actual terminal outcome. |
| Verify the live path | On a fresh deployment, one real trigger creates one run. Verify the selected workflow and agent IDs, model call, Gateway tool outcome, and durable completion. Exercise duplicate delivery and denied access. |

Phase one is a one-agent vertical slice of the existing #295 baseline. The
[workflow v1 status](../architecture/workflow-v1-status.md) tracks evidence and
the full criteria for earning baseline credit.

## Phase two target

Run a dedicated LangGraph worker container that starts a workflow when a ToolGate
trigger fires. ToolGate owns workflow, agent, model, input, trigger, RAG, and
external history configuration. The deployment injects observability settings
through an environment file. The worker queries the Gateway for an authorized
workflow revision, builds the graph from that definition, and runs it against
models hosted in separate llama.cpp containers. All agent tool calls use ToolGate
Gateway. A ToolGate deployment profile recreates the configuration on a fresh
installation, and New Relic shows which workflow and agent step made each tool call.
In phase two, a Chat UI starts workflows through the same admission path and
continues conversations using the workflow's configured history store.

## Components and boundaries

| Component | Responsibility |
| --- | --- |
| ToolGate Control and Console | Author, validate, publish, and audit configurations. Import and export profiles. Display model test results and workflow run status. |
| ToolGate Gateway | Expose authorized runtime configuration, trigger delivery, and run status routes. Authorize and dispatch MCP `tools/call` requests under the configured agent identity. |
| LangGraph worker container | Consume trigger deliveries, obtain a resolved definition and inputs from the Gateway, persist jobs, compile graphs, execute model and tool steps, and report outcomes. |
| PostgreSQL | Store durable workflow jobs, worker claims, and LangGraph checkpoints. |
| External retrieval and history services | Provide approved knowledge sources and durable conversation history using workflow-scoped access. |
| External llama.cpp containers | Serve inference over approved OpenAI-compatible endpoints reached by the Gateway model proxy. Operators deploy the containers and supply model files. |
| OpenTelemetry Collector and Prometheus | Export traces to New Relic, Langfuse, LangSmith, and Phoenix. Expose operational metrics for Prometheus. |

The [hot-folder example](../../examples/langchain/trigger_agent.py) provides the
starting point for trigger streaming and Gateway tool calls. The
[deployment profile flow](../deployment/profiles.md) provides startup application
and provisioning status. The workflow worker and its contracts are new work.

## Workflow and agent configuration

Each workflow owns one or more model configurations. A model ID such as
`extract-small` is local to its workflow. A different workflow can define the
same ID with a different served model, endpoint, or inference settings. Multiple
workflow models can reference the same approved external endpoint.

| Record | Fields and behavior |
| --- | --- |
| Endpoint | Approved base URL, provider, authentication and CA references, timeouts, and egress policy. Endpoint definitions can be shared across workflows within the tenant. |
| Workflow model | Local model ID, endpoint reference, served model name, capabilities, inference parameters, and revision. |
| Workflow | Stable ID, published revision, owned models, default model sequence, agents, graph nodes and edges, routing rules, input and output schemas, and execution limits. |
| Agent | Prompt, ToolGate identity and tool bindings, allowed models selected from its workflow, default model sequence, and input and output mappings. |
| Variable | Name, type, required flag, validation rules, classification, and source. Sources include trigger fields, profile values, prior step outputs, and secret references. |
| Trigger binding | Trigger assignment or subscription, `workflowId`, input mapping, and activation state. |
| RAG configuration | Named retriever definitions owned by the workflow, including source references, retrieval mode, optional embedding configuration, result limits, filters, and output mapping. |
| External history configuration | Workflow-level store reference, access credentials reference, conversation scope, read and write policy, context limits, and retention settings. |

Example configuration for one workflow:

| Scope | Models available | Default sequence |
| --- | --- | --- |
| Invoice Processing workflow | `extract-small`, `reason-large`, `review-small` | `extract-small` → `reason-large` |
| Analyze agent | `extract-small`, `reason-large`, `review-small` | `extract-small` → `reason-large` |
| Review agent | `reason-large`, `review-small` | `review-small` |

Agents can inherit the workflow's model list and sequence or specify their own.
Validate that every agent model belongs to its workflow and every sequence step
belongs to that scope's effective model list. If an agent narrows its list and
excludes a model in the inherited sequence, require an explicit sequence before
publishing. Workflow-level model nodes can use the workflow sequence directly.

Every invocation starts at the first step of its effective sequence. The graph
runs each step in order and passes output through the declared input mapping.
Persist completed steps and their outputs for recovery. Define the retry and
failure behavior for each step. The proposed default is ordered execution of all
steps, with a new agent invocation starting the sequence again.

### Model management in ToolGate

Add **Add**, **Edit**, **Test**, and **Delete** actions under each workflow's
**Models** section, with corresponding Control API operations. Test from the
Gateway model proxy's network using the approved endpoint. Check readiness, discover the
served model, run a small inference, and probe tool calling or structured output
when the model configuration declares those capabilities. Report results in the
Console. [llama.cpp server API](https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md)

Publish edits as new revisions. Require removal of published references before
deleting a model. Retain the revisions required by active runs. Validate endpoint
references and enforce the worker's outbound policy before making inference or
test requests.

## Trigger admission and durable execution

1. Subscribe through the Gateway using `toolgate.triggers.v1`. Resolve each
   delivery through its binding to `workflowId`. Authorize admission and return
   the published workflow revision, resolved configuration, and validated
   inputs. Record a durable rejection for permanently invalid input.
2. Persist the job, pinned configuration, trace context, and `runId` before
   acknowledging the trigger cursor. Deduplicate by tenant, binding, and event
   identity. On redelivery, reuse the existing admission result. Preserve cursor
   order when acknowledging deliveries.
3. Claim the job and compile the declarative workflow into a LangGraph
   `StateGraph` using supported node types. Validate references, routing, model
   order, and execution limits at publication. Use the pinned revision on every
   attempt and a stable run identity for PostgreSQL-backed checkpoints.
   [LangGraph persistence](https://docs.langchain.com/oss/python/langgraph/persistence)
4. Invoke external models through the Gateway model proxy after checking input
   classification and endpoint policy. Invoke every agent tool through MCP `tools/call` at the Gateway under
   that agent's configured identity and current grants. The Gateway authorizes
   and dispatches to the Tool Host or enrolled device.
5. Report status, outputs, and failure details through the Gateway for display
   in ToolGate. On restart, recover the job and resume from its checkpoint. If a
   tool operation has an unconfirmed outcome, record it for reconciliation
   before repeating the operation.

## RAG and external conversation history

### Retrieval configuration

Allow a workflow to define zero or more named RAG configurations. A workflow node
or an agent step can select any retriever allowed by the workflow and map its
query from a trigger input, chat message, or previous step output. Return bounded
passages with source references so downstream steps can cite them. Validate
source and embedding references before publication. Enforce the caller's source
permissions and data classification when retrieving passages and sending them
to a model. Define timeouts and the behavior for empty results and retrieval
failures per step.

Route agent-invoked retrieval tools through Gateway MCP `tools/call`. A
declarative retrieval node also uses an approved Gateway route or Gateway tool
binding. Include retrieval latency and status in tracing and metrics using the
deployment's content capture policy.

### Workflow-level external history

Let each workflow select an external history store and configure the conversation
key, retention, and history window. Start with a PostgreSQL-backed history
provider that can point to an external database. Scope history by tenant,
workflow, and authorized conversation. Load a bounded history window before
execution and write admitted messages and finalized replies using stable message
IDs so retries cannot append duplicates. Apply the workflow's policy for store
unavailability and authorize history reads and writes on every request.

History supports conversations across runs. LangGraph checkpoints support
resuming an individual run. Export the history store configuration and credential
references with the workflow. Store conversation records in the external provider.

## Distributed tracing and metrics

In phase two, New Relic must show the relationship between a workflow's agent step and the
actual tool execution. Extract the trigger's W3C trace context when available and
persist it with the job. Create a client span per LangGraph tool invocation and
inject its `traceparent` into the MCP request. Verify propagation through Gateway
authorization and dispatch to the Tool Host or device. For long-running or
resumed execution that starts a new trace, link it to the prior context and
retain the run ID for lookup.

```text
workflow.run
  agent.step
    langgraph.tool_call
      toolgate.gateway_request
        toolgate.gateway_authorize
        toolgate.gateway_dispatch
          execute_tool <tool>
```

Use the existing [ToolGate Insights Collector](../../deploy/insights/collector/README.md)
and [New Relic deployment](../../deploy/newrelic/README.md) as starting points.
Export LangGraph spans and ToolGate's detailed execution spans to the same New
Relic account. Align sampling so the workflow and tool spans are retained
together in acceptance tests. Correlate using `trace.id`, parent span IDs,
workflow run ID, and ToolGate invocation and execution IDs where available.
[ToolGate correlation ADR](../adr/031-execution-correlation-operator-insights.md) · [New Relic span mapping](https://docs.newrelic.com/docs/opentelemetry/best-practices/opentelemetry-best-practices-traces/)

Send workflow, agent, and model traces through the Collector to Langfuse,
LangSmith, and Phoenix. Configure destinations independently with bounded queues
and retries. Record a client span for each llama.cpp request. Gate capture of
prompt, input, tool payload, and output content on the destination's policy.
[Langfuse OTLP](https://langfuse.com/integrations/native/opentelemetry) · [LangSmith OpenTelemetry](https://docs.langchain.com/langsmith/trace-with-opentelemetry) · [Phoenix tracing](https://arize.com/docs/phoenix/learn/tracing/how-tracing-works)

Extend the [Prometheus deployment](../../deploy/prometheus/compose.yaml) with queue
depth, trigger-to-start latency, workflow duration, failures, retries, checkpoint
resumes, model latency, available token counts, and exporter errors. Keep run and
event IDs in traces and logs to bound metric cardinality.

### Environment file injection

Provide a documented `observability.env.example` with placeholder values. Compose
injects a deployment-supplied `observability.env` into the Collector and passes
the relevant OpenTelemetry settings to the worker and ToolGate services. Use the
file for destination enablement, OTLP endpoints, workspace or project settings,
credential bindings, sampling, content capture policies, and Prometheus settings.
Keep vendor credentials scoped to the Collector. Ensure each service receives
only the values it needs. Provision the environment file independently for each
deployment and apply changes through service reconfiguration or restart.

## Profile export, import, and container initialization

Extend the Deployment Profile contract to include workflows and their owned model
definitions, shared endpoint definitions, agent model lists and sequences,
variables, trigger bindings, tool references, RAG definitions, and external
history configuration. Export
stable IDs, ordered sequences, approved portable values, and references to
secrets and external services. Credentials, model weights, and execution history
stay in their respective stores.

On container initialization, Control validates the complete profile, resolves
references, applies the definitions idempotently, and reports provisioning status.
Activate each workflow after its required references and permissions are in place.
The worker starts with its Gateway URL and service identity, database connection,
and Collector connection. It obtains application configuration from the Gateway.
Recreating a stack requires the referenced identities, credentials, and external
model containers, retrieval sources, and history stores to be provisioned for
that environment. Observability settings come from the deployment environment
file.

## Phase two delivery and acceptance

| Stage | Done when |
| --- | --- |
| 1. Contracts and profile provisioning | Generated contracts and Control provisioning support workflows, models, agents, typed variables, trigger bindings, RAG, and external history settings. Profile export and fresh import preserve IDs and sequence order. Invalid references fail before activation and repeated application is idempotent. |
| 2. Model management | The Console supports Add, Edit, Test, and Delete per workflow. Tests use two external llama.cpp containers. Two workflows can use the same local model ID with different settings. An in-use model is protected from deletion. |
| 3. Configurable trigger execution and New Relic | A real hot-folder trigger starts a two-agent workflow by ID. It resolves inputs and configuration through Gateway, calls two models in the configured order, and makes two authorized MCP calls through Gateway. New Relic shows both tool calls with the corresponding Gateway and execution spans, shared trace IDs, and correct parentage. A denied call shows the Gateway denial without an execution span. |
| 4. Recovery, RAG, history, and observability | Duplicate deliveries create one run and restart resumes the pinned revision. A workflow node and an agent step each retrieve from an approved source. Two runs for the same authorized conversation share history, and replay creates no duplicate messages. Tests cover cross-tenant access, competing claims, and crashes around acknowledgment and tool effects. Environment-file settings enable Langfuse, LangSmith, Phoenix, and Prometheus. A destination outage leaves workflow execution available. |
| 5. Fresh deployment acceptance | Export the working profile, start a fresh Compose stack with external services, secret bindings, and `observability.env`, apply the profile on startup, and replay the trigger successfully. Run contract regeneration checks, applicable component gates, and the full Compose scenario. |

Before implementation, review the workflow contract and runtime trust boundaries
in an issue and ADR. Verify through the applicable gates in
[CONTRIBUTING.md](../../CONTRIBUTING.md). Where implementation changes issue #274
modules, follow that issue's branch, verification, and progress workflow.

## Phase two Chat UI

Add a Chat UI that submits messages to an authorized Gateway start route with
`workflowId`, `conversationId`, and a client message ID. The Gateway validates
workflow access, conversation ownership, and inputs, then admits the request
through the same durable run path as triggers. Use the client message ID to
deduplicate retries. Return `runId` and stream progress and replies through an
authorized Gateway route. Apply the published workflow's model sequences, RAG
configuration, and external history policy on each turn.

Phase two is complete when a user can start an allowed workflow, receive a reply,
reload the UI, and continue the same conversation from external history. Verify
that a new conversation starts with its own history, unauthorized access is
denied, duplicate submission creates one run, and Gateway tool calls from a chat
turn remain traceable in New Relic.

## Decisions for review

- Confirm that a model sequence runs every step in order, with each new agent
  invocation starting at the first step.
- Confirm the supported graph node types and routing conditions for the first
  release.
- Select the New Relic account and region and the Langfuse, LangSmith, and
  Phoenix destinations for acceptance. Define each destination's content policy.
