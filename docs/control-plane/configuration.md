# Control Plane configuration and operations

The required external inputs are PostgreSQL and an identity provider issuing RS256
access tokens. There is no built-in password database, default account or generated
production credential. Directory users describe the organization; they do not create
identity-provider users or change their token roles. Disable/revoke IdP accounts in
the IdP. Directory changes require explicit signed bundle publication to reach
Gateway. See [publication, trust configuration and operations](policy-bundles.md).

| Environment variable | Meaning |
|---|---|
| `QUARKUS_DATASOURCE_JDBC_URL` | PostgreSQL JDBC URL; production requires `sslmode=verify-full` and a trusted CA |
| `QUARKUS_DATASOURCE_USERNAME`, `QUARKUS_DATASOURCE_PASSWORD` | Dedicated runtime login, member of `toolgate_control_runtime` |
| `QUARKUS_FLYWAY_USERNAME`, `QUARKUS_FLYWAY_PASSWORD` | Separate schema owner/migration login |
| `MP_JWT_VERIFY_PUBLICKEY_LOCATION` | Local mounted PEM RSA public key, at least 2048 bits; no signing key |
| `MP_JWT_VERIFY_ISSUER` | Exact HTTPS identity-provider issuer |
| `MP_JWT_VERIFY_AUDIENCES` | Dedicated administrative access-token audience |
| `TOOLGATE_CONTROL_MAX_RECORDS` | Active records across all kinds per tenant, 1–512; default 512 |
| `TOOLGATE_CONTROL_MAX_CONFIG_BYTES` | Serialized record bytes per tenant, 1024–1048576; default 1 MiB |
| `TOOLGATE_CONTROL_DEVELOPMENT_MODE` | Default false; explicit isolated local tests may use PostgreSQL without TLS |
| `QUARKUS_HTTP_PORT`, `QUARKUS_MANAGEMENT_PORT` | Defaults 8082 and 9092 |
| `TOOLGATE_CONTROL_TRACE_EXPORT_ENABLED` | Default false; enable sanitized outbound traces at runtime |
| `QUARKUS_OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | Explicit trusted collector URL; use HTTPS in production |
| `QUARKUS_OTEL_EXPORTER_OTLP_TRACES_PROTOCOL` | `grpc` (default) or `http/protobuf` |
| `TOOLGATE_CONTROL_MARKETPLACE_*` | Optional OLO Orgs Marketplace import; see [Marketplace integration](marketplace-integration.md) |

Tokens require signed `sub`, `tenant_id`, `groups`, `iat` and `exp` claims. A token's
maximum lifetime and age are 900 seconds, with no expiry skew or future issue time.
Groups `toolgate-reader` and `toolgate-admin` may read directory/export/OpenAPI data;
only `toolgate-admin` may mutate, import or read audit entries. Tenant identity never
comes from a request body or header. Invalid identity claims fail closed.
Rotate verification keys through an explicitly coordinated IdP/key mount rollout.
There is no discovery/JWKS fetch, key cache or interactive login flow in Module 02.

Only 8082 belongs behind a trusted HTTPS ingress/proxy. Keep 9092 private to probes
and authorized monitoring. Proxy header forwarding and CORS are disabled. Do not
expose either internal cleartext listener directly to an untrusted network.
`/q/health/live`, `/q/health/ready` and `/q/metrics` run on 9092. Database loss makes
readiness fail and APIs return `DEPENDENCY_UNAVAILABLE`, without leaking SQL errors.
Logs contain fixed status/failure code, safe application call site, server request ID and trace ID; request bodies, identities,
tokens, paths and query strings are excluded. W3C `traceparent` propagates through
Quarkus/OpenTelemetry. Audit request IDs correlate with response headers and logs.
Before any configured span exporter receives data, an SDK customizer reduces spans
to trace IDs, timing, status, a fixed name, HTTP method/status and server request ID.
Raw URLs, paths, query strings, events, links, exception descriptions and trace-state
values are removed. SDK unit and real OTLP protobuf receiver tests verify this boundary.
The image builds the `cdi` exporter; runtime export remains disabled unless explicitly
enabled. Exporter selection is a build-time setting in
[Quarkus 3.40](https://quarkus.io/version/3.40/guides/opentelemetry/).

Requests are limited to 2 MiB, headers to 16 KiB, data nesting to 32, and pages to
100 entries. Each replica has a 16-connection pool with a 3-second acquisition
deadline. SQL and tenant-lock deadlines are 5 and 3 seconds, with a 10-second
driver socket deadline. Shutdown drains for 20 seconds; the chart allows 30 seconds.
Restrict ingress connections/rates at the organization edge. These are administrative
APIs, not the Gateway authorization hot path.

Startup refuses missing auth inputs, weak keys, identical migration/runtime logins
and runtime permission to change audit entries or create schema. Provision the
NOLOGIN role `toolgate_control_runtime` before migrations. The migrator owns the
dedicated database and `public` schema; the runtime login inherits the migration
grants only. Production migrators do not need `CREATEDB`; the isolated test helper
uses it to create fresh databases for clean-install/upgrade proofs. Supply credentials
via a secret manager/Kubernetes Secret, never the chart values or repository.

Use [deployment and upgrades](upgrades.md) for HA, migration and recovery. For a 503,
check database connectivity/TLS, NetworkPolicy DB/DNS peers, pool pressure and
readiness. For a 401, check issuer/audience/key, token claims and clock synchronization.
For a 409, refresh revisions and validate references, retired IDs and tenant quotas.
Do not bypass these checks to make a deployment ready.

Module 05 approval behavior defaults disabled. Enable bundle signing and set the
four `TOOLGATE_CONTROL_APPROVAL_*` fields in [approval configuration](approvals.md).
The dedicated approver role and signed `user_id` cannot be replaced by admin role.

## Client check-in

Source: [Tool SDK plan §17.1 and §17.2](../sdk-plan/plan.md). Phase A ships with
milestone M1; Phase B below is still a specification.

Control returns the check-in interval in each check-in reply. Clients that send
`X-ToolGate-Poll-Interval-Unit: milliseconds` receive `nextIntervalMs`, which takes
precedence; older clients receive `nextIntervalSeconds`, the same interval rounded up
to whole seconds (at least 2). The endpoint client accepts any value from 500 ms to
1 hour and rejects anything else (`apps/endpoint-client/src/service.rs:859-865`). The
client opens its WebSocket only after a poll delivers a job.

### Five settings, not one

| Setting | Purpose |
|---|---|
| Heartbeat interval | Liveness, inventory and status reports |
| Job notification transport | How a device learns a job exists: WebSocket push (preferred) or fallback poll |
| Job pickup timeout | How long a queued job may wait for its device before it expires, or is re-queued if idempotent |
| Execution deadline | Per tool, from the manifest, capped by the platform ceilings |
| Offline threshold | When a device counts as unavailable |

A WebSocket disconnect is not device unavailability. A dropped socket puts the device
in `DEGRADED` for a grace period, with fallback polling. Non-idempotent leased jobs
must never be rescheduled to another device because of a socket drop; they go to
`OUTCOME_UNKNOWN` at lease expiry. Only queued, not-yet-leased jobs may move, and only
if the binding allows it.

### Phase A variables (no client change)

Phase A makes the interval configurable and works with clients already deployed.

| Environment variable | Meaning | Quickstart | Enterprise default |
|---|---|---|---|
| `TOOLGATE_CONTROL_CLIENT_CHECKIN_MS` | Value sent as `nextIntervalMs`; must be 500 to 3600000 | 500 | 2000 |
| `TOOLGATE_CONTROL_CLIENT_CHECKIN_JITTER_PCT` | Random spread added to each interval, in percent | 0 | 20 |
| `TOOLGATE_CONTROL_CLIENT_OFFLINE_AFTER_MS` | Time without a check-in before a device counts as offline | 120000 | max(120000, 6 × check-in) |

In Phase A an idle device only learns of a job at its next check-in, so the check-in
value is the job pickup latency.

Each reply picks an interval at random within check-in ± jitter. A check-in that
arrives sooner than half the shortest such interval is rejected with 409 as a request
burst (250 ms at the Quickstart default, 800 ms at the enterprise default). A device
with no check-in for the offline threshold gets no local tools in discovery and no
new client tool jobs.

**Validation rule.** Check-in plus its maximum jitter plus a 5 second execution margin
must not exceed the 30 second synchronous call deadline, and the offline threshold
must exceed the longest interval. Otherwise Control refuses to start with a named
configuration error, rather than run with a value that makes synchronous client tools
time out. Longer client work must use durable tasks.

**Where it is set.** Helm values `control.clientCheckIn.checkInMs`,
`control.clientCheckIn.jitterPct` and `control.clientCheckIn.offlineAfterMs` (0 means
the derived default); the chart refuses to render values that break the rule above.
Quickstart sets the Quickstart column in its compose files and supervisor. With none
of the variables set, Control uses the enterprise defaults. These are
environment-owned values: they cannot be changed through the settings API or
configuration import. `GET /api/control/v1/settings` returns them as `clientCheckIn`
with `source` (`DEFAULT` or `ENVIRONMENT`), and the console shows them read-only under
Configuration > Device (see [Configuration menu](configuration-menu.md)).

### Phase B variables (future, needs a client release)

Phase B is not part of M1. It makes a persistent WebSocket the steady state, with
server push for jobs, permission changes and kill entries. Older clients keep Phase A
behavior. Planned variables:

- `TOOLGATE_CONTROL_CLIENT_SOCKET_MODE` (`persistent`)
- `TOOLGATE_CONTROL_CLIENT_HEARTBEAT_MS` (enterprise 30000)
- `TOOLGATE_CONTROL_CLIENT_FALLBACK_POLL_MS` (used only while the socket is down)
- `TOOLGATE_CONTROL_CLIENT_JOB_PICKUP_TIMEOUT_MS`
- `TOOLGATE_CONTROL_CLIENT_SOCKET_GRACE_MS`

For scale: idle load at 10,000 devices is about 20,000 requests/s at 500 ms polling,
5,000 at 2 s (Phase A), and about 333/s of heartbeats at 30 s (Phase B).
