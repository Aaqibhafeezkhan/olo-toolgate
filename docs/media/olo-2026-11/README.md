<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# OLO console and multi-agent observability — November 19, 2026

Unaltered browser JPEGs were captured from the existing `debug/profiled`
installation at port 18090 (UI source `ed13f0f7`). The matching published-image
deployment is not their source. Configured bots, device and policies are demo
data, not blank-install defaults. Cloud consoles were signed in by the owner.
No password, API key or private model thinking is captured.

The [multiagent-incident bot](../../../bot/configurations/multiagent-incident/README.md)
runs `incident-triage-agent`, then `incident-summary-agent`, using real local
`qwen3:0.6b` inference. Its successful run is
`run-f7672b57-3229-4afa-8c59-2d715a498fc2`; all three cloud API readbacks match
eight spans with trace ID `386029e4f45057cfd8c07c24c5a2d628`.
[Exact revision and evidence](evidence.json).

| View | Original |
|---|---|
| Overview and navigation | [Overview](console/overview.jpg) |
| Published bot | [Bots](console/bots.jpg) |
| Live progress | [Running](console/multiagent-running.jpg) |
| Triage and handover | [Result](console/multiagent-output.jpg) |
| Langfuse tree / timeline | [Tree](monitoring/langfuse-tree.jpg) · [Timeline](monitoring/langfuse-timeline.jpg) |
| LangSmith two-agent trace | [Trace](monitoring/langsmith-expanded.jpg) |
| Phoenix matching trace | [Trace](monitoring/phoenix-trace.jpg) |

The cloud exporter sends structural metadata only: absent prompts/results,
tokens, actual model identifiers and costs are intentional. A displayed `$0`
is not measured inference cost. Model-generated severity/prose needs review;
this bot performs no remediation or tool effects. Two earlier configuration
trials failed because the second agent lacked delegated eligibility; the
successful configuration explicitly enables it.

[Complete current tours](../TOURS.md), [capture times/URLs/checksums](captures.json)
and [refreshed old-path aliases](aliases.json) provide provenance. The manifest
uses each saved file's UTC capture-write time. GIF captions/scaling are generated;
linked JPEG originals remain unchanged. Earlier PNG and retired approval/native
additional client/Operator views are indexed in the [media guide](../README.md).

To repeat: build/import this bot, configure local models using its README,
publish, paste its exact incident input and follow both agents in Run. Capture
loaded pages and the same run in the signed-in monitoring consoles; update the
manifest with real run IDs, source revision and checksums. Do not substitute
mock model results or dashboards. Rebuild tours using
`python tools/docs/build_console_tours.py` from the repository root.
