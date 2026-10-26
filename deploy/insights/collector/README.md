# Insights export Collector

Operator Insights can export DETAILED spans and per-minute metrics from Control over OTLP/HTTP JSON
([ADR 031](../../../docs/adr/031-execution-correlation-operator-insights.md) §6, [Insights guide](../../../docs/control-plane/insights.md)).
Control sends to one endpoint. Point it at an OpenTelemetry Collector, which handles vendor credentials, retries and
fan-out. Control never holds a Datadog or New Relic key.

| File | Sends to |
|---|---|
| `datadog.yaml` | Datadog: traces, metrics and APM trace metrics through `datadog/connector` |
| `newrelic.yaml` | New Relic, over OTLP |
| `both.yaml` | Datadog and New Relic. Each exporter queues and retries on its own. |
| `compose.yaml` | Example Collector service for Compose deployments |

The image is pinned to `otel/opentelemetry-collector-contrib:0.120.0`. All three configs pass
`otelcol-contrib validate` on that image. In production, pin it by digest (`INSIGHTS_COLLECTOR_IMAGE`).

## Connect Control

| Setting | Helm value | Meaning |
|---|---|---|
| `TOOLGATE_INSIGHTS_OTLP_ENDPOINT` | `control.insights.otlp.endpoint` | Collector base URL, for example `http://insights-collector:4318`. Empty disables export. |
| `TOOLGATE_INSIGHTS_OTLP_ENVIRONMENT` | `control.insights.otlp.environment` | `deployment.environment.name` on every export |
| `TOOLGATE_INSIGHTS_OTLP_SERVICE_NAME` | — | `service.name`. Defaults to `olo-toolgate`. |

Spans are exported only in DETAILED mode, and only for calls that tail sampling keeps. Metrics are exported in BASIC
and DETAILED.

Export is asynchronous and bounded. Control queues up to 20,000 spans and backs off when the endpoint fails; when the
queue is full, the oldest spans are dropped. A minute of metrics is claimed under the tenant's lock before it is sent,
so replicas never send the same minute twice. A failed send is not retried. After an outage, at most the last hour is
backfilled.

## Trust boundary

The export is cross-tenant operator data. It carries tenant, invocation and execution IDs, tool IDs, route IDs, HTTP
status codes and allowlisted error codes. It never carries arguments, results, URLs, headers, user text or fencing
tokens.

- Run the Collector inside the same trust zone as Control: the same Compose network, or a cluster-local Service.
  Do not publish port 4318.
- Restrict ingress to Control, for example with a NetworkPolicy that allows only Control pods.
- Plain `http://` is acceptable only on that private network. Across hosts, terminate TLS at the Collector and use
  `https://`; Control trusts the JVM's default CA store.
- To hide tenant IDs from a vendor, hash `toolgate.tenant.id` in the Collector with a `transform` processor before
  the exporter, for example `set(attributes["toolgate.tenant.id"], SHA256(attributes["toolgate.tenant.id"]))`.

## What arrives

### Traces

Each kept call is one trace. Its trace ID is the caller's W3C trace when one was supplied.

| Span name | Kind | Source | Notes |
|---|---|---|---|
| `toolgate.gateway_request` | Server | Gateway | The MCP request |
| `toolgate.gateway_authorize` | Client | Gateway | Authorization with Control |
| `toolgate.gateway_dispatch` | Client | Gateway | Waiting for the executor |
| `execute_tool {tool}` | Internal | Tool Host or client | One per attempt. OpenTelemetry GenAI convention. |
| `toolgate.host_egress` | Client | Tool Host | One per brokered route call |
| `toolgate.host_admission` | Internal | Tool Host | Reserved |

Attributes:

- `toolgate.tenant.id`, `toolgate.invocation.id`, `toolgate.execution.id` and `toolgate.span.source`
- `gen_ai.operation.name=execute_tool`, `gen_ai.tool.name` and `gen_ai.tool.call.id` on execution spans
- `toolgate.route.id`, `http.response.status_code` and `error.type` where they apply

### Metrics

All metrics use delta temporality and one-minute points.

| Metric | Type | Attributes |
|---|---|---|
| `toolgate.insights.calls` | Sum (monotonic), `{call}` | `toolgate.tenant.id`, `gen_ai.tool.name`, `toolgate.outcome` |
| `toolgate.insights.duration` | Histogram, `ms`, 20 explicit bounds | Same as above |
| `toolgate.insights.admissions` | Sum (monotonic), `{request}` | `toolgate.tenant.id`, `toolgate.admission.category` |

Calls count in the minute they were accepted, with the outcome they had when that minute was exported. A correction
that arrives after a minute was exported is visible on the Insights page but is not re-sent.

## Starting queries

These are starting points, not tested dashboards. Vendor-side validation is part of the integration testing stage
(Stage 7). Delivery to either vendor is not yet verified; see the
[Stage 7 report](../../../docs/control-plane/insights-stage7.md).

- **Datadog.** Traces appear in APM under service `olo-toolgate`. Example metric queries:
  `sum:toolgate.insights.calls{toolgate.outcome:failed} by {gen_ai.tool.name}.as_count()` and
  `p95:toolgate.insights.duration{*} by {gen_ai.tool.name}`. Datadog lower-cases tag values.
- **New Relic.** Spans land in `Span` and metrics in `Metric`. Examples:
  `FROM Metric SELECT sum(toolgate.insights.calls) FACET toolgate.outcome TIMESERIES` and
  `FROM Metric SELECT percentile(toolgate.insights.duration, 95) FACET gen_ai.tool.name TIMESERIES`.

## Helm

The chart does not deploy a Collector. Use the OpenTelemetry Collector Helm chart with one of these files as its
config:

1. Create a Secret for `DD_API_KEY` or `NEW_RELIC_LICENSE_KEY`.
2. Expose it to the Collector as environment variables.
3. Set `control.insights.otlp.endpoint` to the Collector's cluster-local Service URL.
