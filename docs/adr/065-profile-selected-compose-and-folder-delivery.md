<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 065: Profile-selected Compose and folder delivery

Status: Implemented for the bounded local stack; executed acceptance and remaining limits are recorded in workflow-v1-status.md.

The shared Compose deployment selects a read-only configuration directory. Each
directory contains one profile, sample input and README. Runtime/model/trigger/
folder/monitoring settings are scoped to the workflow declaration inside that
profile; the main .env selects only its default configuration. Active dependency
closure is projected without changing retained inactive definitions. Model prompts and agent bindings live in these files, not in
deployment code. Generated credentials, input files and outputs live separately.

The text-summary profile separates a file agent from a summarization agent. Every
agent step is bound to the pinned workflow definition and current agent/group
membership and model grants. Tool authority is resolved for the active agent;
the summarization identity has no tool execution binding. Service admission
authority remains required throughout the run.

Workflows may declare ordered `agentStages` referencing unique, bound agents.
The first must be entry-eligible, later agents delegation-eligible, and the count
must fit the handoff budget. `CONTEXT` executes only configured read-only context
sources without inference; `MODEL` runs the agent loop. The fixed graph transfers
the previous completed result and returns the last result unchanged. This avoids
an observed supervisor response that claimed completion without invoking agents.
The model cannot replace the declared sequence. Current server authorization
still applies at each stage; these trusted-runner observations are not independent
server attestation of stage completion. The current lease explicitly carries the
stage list and rejects obsolete snapshots without it.

The fixed runner supports `finish_context` for an agent with successful configured
context reads, returning only those tool values and the admitted source path.
A supervisor can use `inputFromAgent` to hand an already completed agent's result
to a currently eligible agent in the same run. This transfers full text without
requiring the routing model to reproduce it. Unknown, unfinished and foreign-run
references fail closed. References are bounded data identifiers, not expressions,
filesystem paths, imported code or authority. They never include adapter tokens.

An explicitly trusted folder agent adapter owns the host inbox, processed and
summary directories. It snapshots a dropped file into the device's private files
volume. After a confirmed successful workflow result, it writes the configured
summary projection and archives that exact input. Model output cannot select
filesystem paths. A persistent journal binds source digest, private device path,
run and projected outputs, refuses changed inputs/collisions, and permits recovery
of the same committed projection. Failed or unconfirmed workflows leave inputs
unprocessed. This operator-side delivery is separate from native tool mutation
finality; it neither qualifies native write tools nor grants an ambiguous retry.

The adapter is part of the file agent deployment and is named explicitly in its
configuration. It has narrowly mounted folders and no model/workload credentials
or Docker socket. It independently checks terminal results with Control using a
mounted operator result-reader credential (the current result API requires a
tenant Super Admin). This trusted local deployment role and broad credential
must be disclosed; it is not an unprivileged agent sandbox.
Native model-driven file reads still pass through Gateway and current tool grants.
The untrusted native tool containers keep their existing sandbox and no mounts.

Gateway requests JSON-schema-constrained decoding and independently validates
the closed canonical decision union before returning a proposal to the runner.
This addresses malformed decisions observed with unconstrained local inference.
Backend support for the response format is required; no weaker fallback is
introduced. Schema validity supplies no delegation, model or tool authority.

External monitoring must not collect input contents, prompts, results, credentials
or provider response bodies by default. Native LangSmith tracing stays disabled
in the runner; deployment instructions distinguish metadata telemetry from full
LLM observability and do not claim unsupported integrations are enabled by keys.
