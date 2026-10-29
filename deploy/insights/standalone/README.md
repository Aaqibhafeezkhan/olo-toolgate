# Shared standalone monitoring runtime

`compose.yaml` supplies QuickStart, the public-CA publisher, and the native device
to the New Relic, Datadog and Prometheus Compose projects. Each project declares
its own private network, ports, volumes and collector. This directory is source
configuration, not another running stack dependency.

Each vendor folder has `start.bat`, `stop.bat`, and `generate-data.bat`. Start
performs evaluation bootstrap and three rounds of real Hello World success plus
unauthenticated denial. Generate-data repeats those cases without reconfiguring
access. `--bootstrap` reapplies the scoped fixture through the normal review flow
and rotates its credential via QuickStart's reauthorization endpoint.

The native device checks in separately. Hello World executes on QuickStart's
bundled Tool Host; this fixture does not claim native-device package execution.
The root request, authorization, dispatch, and execution spans constitute the
instrumented call path. Method-level, database, or external-call spans require
corresponding instrumentation and a use case that makes those calls.

Validation on 29 October 2026:

- All three resolved Compose configurations passed and contained no external network.
- New Relic and Prometheus started with their own fresh QuickStart/device volumes.
- Their devices enrolled and reached ACTIVE with accepted check-ins.
- Real success cases returned 200 and invalid-token cases returned the expected 401.
- Repeated bootstrap and credential renewal passed after existing data was retained.
- New Relic UI displayed four-span Hello World call traces; captures are in
  `docs/media/screenshots/newrelic-*-full.jpg`.
- Prometheus ingested real `toolgate_insights_calls` metrics for its own service.
- Grafana's provisioned Tempo data source returned real stored call traces.
- Datadog's shared runtime configuration passed validation; vendor delivery was
  not tested because no Datadog API key is configured locally.
