<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Workflow monitoring

All operational settings are under the enabled workflow's `deployment.monitoring`
in `workflow-profile.json`. The main `.env` still contains only `CONFIGURATION`.
The helper derives the collector configuration and Compose values on deployment.

| Tool | What to inspect |
| --- | --- |
| Worker/listener/folder logs | Run states, agent/tool observations and delivery completion: `python manage.py logs` |
| Control result API | Current tenant Super Admin: `GET /api/control/v1/workflow-runs/<run-id>`; private/no-store |
| Console audit / Operator Insights | Authorization/execution evidence and operational aggregates |
| Prometheus / Grafana | Counts, latency and bundled dashboards |
| Tempo | Operational traces through Grafana Explore |
| Langfuse / LangSmith | Optional OTLP destinations configured in the profile's collector exporters |
| Datadog / New Relic | Optional collector exporters; repository guides linked below |

## Local tools

Set `deployment.monitoring.enabled` to `true`, choose `mode` (`BASIC`/`DETAILED`)
and `spanSampleRatio`, then run `python manage.py deploy`. Images, Grafana user,
secret reference and ports are also declared there. The supplied values expose
Grafana at http://127.0.0.1:49000 and Prometheus at http://127.0.0.1:49090.
The Grafana password is generated privately at
`state/<selection>/workflow/monitoring/<grafanaSecret>.token`; existing Grafana
volume credentials are not reset by changing the initial password reference.

The profile's `collectorConfig` is the complete OpenTelemetry collector object.
The helper writes it to private generated state and mounts it read-only. The
default exports operational traces to Tempo and metrics to Prometheus. OTLP ports
remain inside the Compose network. Check collector startup/export errors, submit
an actual file, then inspect aggregates and Grafana Explore → Tempo.

## Langfuse and LangSmith

Copy `monitoring/credentials.env.example` to ignored `monitoring/credentials.env`
and enter only actual secret values there. Endpoints, project/agent selection,
exporter headers and pipelines belong in the workflow profile's `collectorConfig`.
The [exporter example](monitoring/profile-exporters.json.example) is a complete
collector object to copy into that profile field and edit; it is not a second
runtime configuration file. Remove unused exporters and their pipeline entries.

For Langfuse, select the correct regional `/api/public/otel` base endpoint in the
profile. `LANGFUSE_BASIC_AUTH` is Base64 of the project's `public-key:secret-key`;
the profile references it as `Basic ${env:LANGFUSE_BASIC_AUTH}` and sets ingestion
version 4. Base64 remains a credential. See the
[official OTLP guide](https://langfuse.com/integrations/native/opentelemetry).

For LangSmith, use the regional API base plus `/otel`. Put the key in
`LANGSMITH_API_KEY`; the profile references it in `x-api-key`. Configure the
registered agent ID/environment directly in the profile's exporter headers.
Project-based workspaces instead use `Langsmith-Project` without agent headers;
multi-workspace keys may also need workspace addressing. Follow the
[official OTLP guide](https://docs.langchain.com/langsmith/trace-with-opentelemetry).

OTLP HTTP exporters append `/v1/traces` to base endpoints; do not append it twice.
Redeploy after editing the profile and confirm ingestion in the actual account.
External ingestion needs your keys and has not been tested against a vendor
account here. Profile-derived exporter syntax is checked separately from delivery.

Native LangSmith content tracing is explicitly disabled by the runner. Setting
tracing environment flags does not enable graph/prompt tracing. This stack exports
platform operational spans; automatic LLM token/cost accounting, agent graphs and
prompt/response traces are not implemented. Default stdout excludes contents,
credentials and provider bodies. Private result files contain the actual output.
Tracing is not independent security attestation. LangSmith's
[privacy guidance](https://docs.langchain.com/langsmith/mask-inputs-outputs) applies
to separately instrumented applications.

## Datadog, New Relic and other OTLP backends

Use the [Datadog](../../datadog/README.md), [New Relic](../../newrelic/README.md),
and [shared collector](../../insights/collector/README.md) guides for their exporter
blocks. Put those blocks and endpoints into the workflow's `collectorConfig`, and
reference private keys from `monitoring/credentials.env`. Add the intended exporter
to its trace/metric pipeline. Supported signals and vendor dashboards differ;
check exporter counters and actual ingestion. The same boundary supports other
OTLP backends. See [Collector configuration](https://opentelemetry.io/docs/collector/configuration/).
