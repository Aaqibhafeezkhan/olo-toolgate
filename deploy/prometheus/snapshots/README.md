# Monitoring smoke test — 2026-10-29

The Prometheus/Grafana stack was started on the existing `olo-demo` Docker network.
Grafana login `admin` / `admin`, database health, the provisioned 8-panel dashboard,
and the provisioned Prometheus data source were verified. The collector scrape
target was UP with no scrape errors. Promtool and collector configuration validation
passed. All 10 PromQL queries across the 8 panels returned series (see `query-checks.json`).

Eight synthetic OTLP/HTTP delta batches were submitted at 15-second intervals;
each returned HTTP 200. Samples were explicitly labelled tenant
`monitoring-smoke-test`, environment `synthetic-test`, and tools `smoke.lookup` and
`smoke.search`. Final cumulative call count was 208, matching the submitted total.
This verifies collector ingestion, delta conversion, Prometheus scraping, dashboard
queries, and display. It does not verify live tool invocation instrumentation.
No existing demo application configuration was changed.

- `grafana-overview.jpg`: Grafana dashboard filtered to synthetic samples.
- `prometheus-counters.jpg`: Prometheus counter query during sample ingestion.

Screenshots were captured while ingestion was running; their counts reflect that
point in time, rather than the final 208 calls. Prometheus estimates of increases
can differ from counter totals because of scrape timing and extrapolation.

The stack's `stop.bat` and `start.bat` were exercised successfully, preserving both
data volumes. The stack was left running. Collector accumulation state resets on
restart; historical samples remain in Prometheus. Synthetic samples remain until
normal retention expires. New Relic and Datadog Compose configurations were checked
with placeholder keys; no vendor delivery was attempted.
