<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Generic ToolGate runtime — execution and trigger foundations

This package runs one fixed LangGraph supervisor/agent loop against a supplied
`ToolGate` adapter. Workflows, agent IDs, explicit models, prompts, context tools,
eligibility, remediation mode and execution limits come from a leased snapshot.
The graph has no use-case IDs, provider keys, provider clients, arbitrary imports
or executable profile expressions. It does not union different agents' tools.

**Integration is pending.** This is a tested executor library, not a working
ToolGate run API, live LangChain/model-proxy integration or complete
end-to-end standalone workflow program. The trigger subscriber described below
uses the existing stream, but durable workflow admission still needs server implementation. There is no default fake adapter, local authority
fallback or runnable-profile claim. V1.1 publication/readiness and V1.2 server
admission/leases, runtime identities, model proxy and real transport must be
implemented before the external message/trigger demo can run end to end.

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
is not a new profile format or a published wire contract. The future transport
must decode the canonical server contract, cap input/response bytes, verify TLS,
use configured infrastructure endpoints, and enforce deadlines without redirects,
credential logging or automatic retries. Prompts and results are sensitive.

The adapter must implement `authorize_step`, `discover_agents`, `discover_tools`, `infer`,
`call_tool` and `finish`. ToolGate must independently enforce current tenant,
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
The future model adapter must validate/translate these decisions from the governed
model response; no such adapter is included yet.

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

No durable workflow checkpoint, automatic workflow failover/replay, cancellation
propagation, model streaming or container image is delivered here.
The [v1 qualification guide](../../docs/testing/workflow-v1-qualification.md)
describes the repeatable scenario controller, private evidence reports and
explicit blocked outcomes while live server integration is missing.
See the [live status](../../docs/architecture/workflow-v1-status.md) and
[architecture plan](../../docs/architecture/langgraph-runtime-plan.md).

## V1.3 host trigger subscriber

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

**That Control endpoint is not implemented by this slice.** A current server's
404/401/503, a timeout, or any non-durable/mismatched receipt stops the connection
without ACK. There is no local admission ledger, fake production success or
fallback workflow. Registration plus an uploaded profile is not yet enough for
end-to-end execution: V1.1 publication and V1.2 admission/run/lease/model transport
remain required. Once available, admitted work belongs to Control's run queue;
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
