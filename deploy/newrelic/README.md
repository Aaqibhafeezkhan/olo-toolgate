# Standalone New Relic ToolGate stack

Each project includes QuickStart (Control, Gateway and bundled Tool Hosts), a
native Linux device, public CA publisher, and collector. Prometheus also includes
Grafana. Networks, runtime credentials and data volumes belong to this project;
no olo-demo, Marketplace, external network or host Docker socket is required.

## Start

Copy `.env.example` to `.env` (start.bat does this if missing). Set NEW_RELIC_LICENSE_KEY and NEW_RELIC_OTLP_ENDPOINT.
Run `start.bat`. It starts the complete stack and runs the local evaluation
bootstrap plus three repetitions of both use cases. QuickStart must become healthy
before the CA publisher and device start. The publisher reads only the public local
trust API and never mounts QuickStart's private data or keys. The device shares
QuickStart's network namespace to use certificate-verified https://localhost:8443;
its identities and state have separate volumes.

QuickStart console: http://localhost:18081. Its private generated passwords are
in `/data/credentials.json` inside this project's QuickStart container. Customize
QUICKSTART_HTTP_PORT and QUICKSTART_TLS_PORT in .env. Each stack defaults to separate
ports. UI ports bind only to localhost. Vendor keys stay in the collector.

## Generate dashboard data

```bat
generate-data.bat --repeat 30 --interval 2
generate-data.bat --case success --repeat 10
generate-data.bat --case denied --repeat 10
```

- Success: a real `ololabs.hello-world/server_greet` request traverses Gateway,
  Control, and the bundled Tool Host. It produces execution spans, counts and latency.
- Denied: the same request with an intentionally invalid credential must return 401.
  It produces admission counts, not a tool-execution span.

Each result prints case, request ID, HTTP status and pass/fail. Unexpected results
cause a nonzero exit. No synthetic OTLP data is injected. The separate native device
is enrolled and checked in; Hello World executes on the bundled Tool Host.

The evaluation bootstrap approves pending enrollments in this isolated project,
waits for its device and bundled Hello World profile, enables the local agent,
and uses the normal configuration review flow to create narrowly scoped greeting
grants. Do not run bootstrap against an unrelated installation. It reads local
admin/reviewer passwords privately and never prints secrets. Existing data is kept.
The workload expires after 24 hours: renew it and generate more calls with:

```bat
generate-data.bat --bootstrap --repeat 10
```

## Observe and stop

In New Relic Traces, filter service.name = 'olo-toolgate-newrelic'. Open a trace for its waterfall.

Allow a few minutes for the minute aggregates and vendor ingestion. Successful
calls produce traces only in New Relic/Datadog; Prometheus stores aggregate metrics.
All stacks use DETAILED collection with full normal-call sampling for evaluation.

Run `stop.bat` to stop this entire project and preserve its volumes. Do not use
`down -v` unless intentionally discarding data and identities. Run `start.bat`
to resume and refresh the evaluation workload. For diagnostics:

```powershell
docker compose --env-file .env -f compose.yaml ps
docker compose --env-file .env -f compose.yaml logs --tail 50
```

Select compatible builds with QUICKSTART_IMAGE and TOOLGATE_DEVICE_IMAGE. Images
default to dev; pin reviewed images for production. This is a local evaluation stack,
not an HA deployment. Vendor credentials, outbound access and ingestion charges
still apply. Prometheus retains up to 15 days or 2GB; its cumulative conversion
state resets with the collector while historical data persists. Grafana data also
persists; its initial password setting does not reset an existing account password.

The old quickstart.yaml / gateway-control.yaml files remain optional overlays for
external deployments and are not used by this standalone stack. The former
TOOLGATE_DOCKER_NETWORK variable is ignored. Use the
[shared collector guide](../insights/collector/README.md) for external deployments.
