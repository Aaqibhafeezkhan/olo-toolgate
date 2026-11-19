<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# OLO observability

Monitoring is installation configuration, outside bots. The optional collector
forwards structural run/agent/model/tool spans to existing cloud projects.
Its keys stay private; never put them in `bot.json`. Core services remain
Quickstart, LangGraph and Device; Ollama/TLS and collector are optional companions.

| Service | Console | Credentials |
|---|---|---|
| Langfuse | [Langfuse Cloud](https://cloud.langfuse.com/) | Project → Settings → API Keys; public + secret keys. [Guide](https://langfuse.com/faq/all/where-are-langfuse-api-keys) |
| LangSmith | [LangSmith](https://smith.langchain.com/) | Settings → API Keys → Create API Key. [Guide](https://docs.langchain.com/langsmith/create-account-api-key) |
| Phoenix | [Phoenix Cloud](https://app.phoenix.arize.com/) | Space → Settings; API key and collector endpoint. [Guide](https://arize.com/docs/phoenix/prompt-engineering/quickstart-prompts/quickstart-prompts-ui) |

For the [published-image launcher](../../deploy/docker/quickstart/dev/README.md),
use `deploy/docker/quickstart/dev/start.bat -WithMonitoring` and that folder's
`credentials/observability.json`. Do not copy another installation's generated
collector files or Admin custody. Provider fields/URLs below are shared.

Run `debug\profiled\start.bat`, choose collector **1**, or run
`debug\profiled\start-monitoring.bat`. First use creates private
`debug/profiled/credentials/observability.json`. Fill the destinations you want
with exact project/space URLs and keys, then rerun. Existing files are reused.
Without cloud destinations the helper can start a local debug collector.
Choice **2** retains the current setup.

See the [complete fields and commands](../../debug/profiled/README.md).
Generated collector config/auth stays private; runtime gets its private endpoint.
Installation instrumentation changes may explicitly recreate LangGraph once;
bot import/switch does not. This setup opens no collector host port.

Run a bot and search its run ID in the selected cloud project. Langfuse exposes
observations, LangSmith its run tree, Phoenix its trace. Spans exclude
prompt/result/secret bodies and currently omit tokens, cost and chosen fallback
attributes. Gateway attempt logs identify the model/deployment/index. Collector
outage drops telemetry without retrying effects or changing a confirmed outcome.
Use OLO's audit/outcome records to establish tool execution.

For a reproducible two-agent example, run
[`multiagent-incident`](../../bot/configurations/multiagent-incident/README.md).
The [recorded walkthrough](../media/olo-2026-11/README.md) shows both agents and
model calls in all three services for the same successful run. The linked
screenshots and API readbacks record eight matching spans per provider. This
establishes bounded telemetry integration, not model quality or tool-effect
finality. A cloud UI's `$0` is not a measured cost when usage is not exported.
