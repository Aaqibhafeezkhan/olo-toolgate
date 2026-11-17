<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Native async summarization graph

This example selects an agent from the published profile through `agentId` in
graph configuration. Its native async node calls the current governed agent
adapter and returns that agent's result. Optional `resultKey` wraps the result.
No model origin, provider credential or lease token lives in the package.

Install with `python tools/workflows/graph_package.py examples/native-graphs/summary
deploy/compose/WorkflowStack/configurations/<case>/graphs`. Copy the printed graph
selection into the workflow's `graph` and set `config.agentId` to its bound agent.
Stage/review/publish that fresh profile and use its existing trigger folder or
manual admission path. Results appear in the stack's documented results folder
and Control run records. The worker needs `--graph-directory /configuration/graphs`.
The selected runtime image must contain the declared dependency versions.

Installing this Python is privileged operator work; digest verification is not
a sandbox or automatic publisher qualification. Native persistent recovery and
interrupts are currently unsupported. Existing Gateway/tool approvals still apply.
