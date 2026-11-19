<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# OLO bot: multiagent-incident

Two separate agents execute sequentially: `incident-triage-agent` classifies the
incident, then `incident-summary-agent` combines the original input and triage
into an operations handover. Both use local Qwen3 through the configured general
model group. Premium OpenRouter remains unattached standby. This text-only bot
uses no device files, tools or trigger packages.

Follow [model setup](../../../docs/help/models.md) and
[import/publish/run](../../../docs/help/run-bots.md). Configure the installation's
local Ollama vault key and approved HTTPS endpoint, build/import this `.olo`,
resolve readiness, review/prepare/publish, then click **Run**. Device membership
and filesystem permissions are unnecessary for this model-only example; see
[device setup](../../../docs/help/device.md) when adding tools.

```powershell
python bot/build.py --configuration multiagent-incident --no-sdk-build --version 1.0.0-demo.multiagent
```

Paste into Bot input:

```text
INCIDENT-204: The production API returned HTTP 503 because the database connection pool was exhausted. Restarting the pool restored service. No customer data was lost. Summarize the incident for an operations handover.
```

The result contains `triage.severity`, `triage.assessment` and `summary`.
The Run panel records both agents and their model calls. Follow the same run in
Langfuse, LangSmith and Phoenix using its `toolgate.run.id`; see
[observability setup](../../../docs/help/observability.md). Exported spans contain
structural metadata, not the incident text or generated prose. Model output
requires human review; no remediation or independent factual verification occurs.

In `bot.json`, the triage agent has `entryEligible: true`. The handover agent
has `entryEligible: false` and `delegationAllowed: true`: the sequential graph
invokes it after the first agent. Keep that eligibility when editing the graph.
[Recorded successful run and three-cloud views](../../../docs/media/olo-2026-11/README.md).
