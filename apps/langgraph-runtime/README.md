<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Generic ToolGate LangGraph runtime — V1.2 foundation

This package runs one fixed LangGraph supervisor/agent loop against a supplied
`ToolGate` adapter. Workflows, agent IDs, explicit models, prompts, context tools,
eligibility, remediation mode and execution limits come from a leased snapshot.
The graph has no use-case IDs, provider keys, provider clients, arbitrary imports
or executable profile expressions. It does not union different agents' tools.

**Integration is pending.** This is a tested executor library, not a working
ToolGate run API, live LangChain/model-proxy integration, trigger listener or
complete standalone program. There is no default fake adapter, local authority
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

No durable checkpoint, automatic failover/replay, cancellation propagation,
streaming, container image or HTTP/trigger registration is delivered here.
See the [live status](../../docs/architecture/workflow-v1-status.md) and
[architecture plan](../../docs/architecture/langgraph-runtime-plan.md).
