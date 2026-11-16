<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 068: Operator-installed native LangGraph packages

Status: implementation in progress; qualification is recorded in workflow-v1-status.md.

## Decision

Profiles select a required native graph package/version/digest, a named factory
and bounded JSON configuration. Control pins that declaration in the run lease.
The generic worker resolves only packages installed by the deployment operator
in its read-only graph directory. A graph factory returns an uncompiled native
StateGraph and receives a private static JSON snapshot. Each run gets a fresh
module namespace, builder and compiled object, with governed adapters through
LangGraph Runtime.context. No live adapter/token enters static configuration.

Package manifests declare file hashes, factories, schemas, runtime API and
dependency requirements. Their canonical manifest digest is the profile pin.
This verifies installed content, not publisher trust: installing Python remains
an explicitly privileged operator action. There is no tenant code upload API,
Python sandbox claim, network package installation or executable code in JSON.

Reuse existing run/step admission, Gateway inference/MCP and terminal result
confirmation. Protected adapter calls are serialized within a run because the
current Control ledger has one active step per run. Native parallel computation
is supported; concurrent protected effects are not advertised. Preserve PREPARE,
current candidate/tool discovery, limits and outcome-unknown stop behavior.

The initial capability set excludes persistent checkpoints, native interrupt
resume and automatic effect retry. A package requesting them is refused before
code loading. A native interrupt cannot be mistaken for a successful result.
This avoids inventing checkpoint fencing, exactly-once effects or a serializer
security boundary that the existing application does not implement. Those
features require separate Control-backed contracts and executed acceptance.

Built-in supervisor/stage execution is itself a graph package. Third-party
operator-installed packages use the same loader and author API, without adding
workflow-specific switches to Control, Gateway, the worker or Compose. Existing
agentStages remains a current built-in execution declaration, not a fallback for
profiles missing the required graph selection. Old graph-less snapshots/profiles
are refused; update fresh fixtures/configurations directly, with no migration.

## UI and deployment

Profile, workflow and agent JSON editors update the existing shared profile
draft, retaining its stage/review/publication and workflow-version behavior.
Invalid editor buffers do not overwrite drafts. Server schema/authority checks
remain mandatory. Graph installation/readiness is separately checked by the
worker/operator tooling; a structurally valid profile is not runtime acceptance.
The existing per-configuration read-only mount supplies graph packages to the
worker. The same image can execute different compatible installed packages.

## Verification and remaining roadmap

Require package tamper/path/schema/dependency negatives, isolated concurrent
run contexts, real native branch/parallel/async execution, governed adapter
denials/unknown-outcome latching, lease transport and profile publication tests,
UI edit/version tests and image/mock-worker acceptance. No #230/#274 stage or
#295 full phase earns credit from this slice. See plan.md for the critique and
remaining feature matrix.

References: [native graph compilation](https://github.com/langchain-ai/langgraph/blob/1.2.20/libs/langgraph/langgraph/graph/state.py),
[per-run context](https://reference.langchain.com/python/langgraph/runtime/Runtime).
