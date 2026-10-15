# ADR 002: Stateless gateway with explicit static trust inputs

> Historical decision. Runtime authority and permit-signing details are superseded
> by [Control online authorization](../architecture/overview.md) and
> [ADR 013](013-tool-host-egress-broker-secrets.md). This is not a current deployment guide.

Date: 2026-10-02. Status: accepted for Module 01.

The gateway must provide a safe authorization path before Control Plane bundle
distribution, ASK approvals and endpoint execution exist. No database or future
service can supply a missing security decision in this module.

Use Axum/Tokio for bounded HTTP ingress and graceful draining. Reuse generated
shared contract models. Compile canonical schemas once with offline references;
disable network/file schema retrieval. Keep policy, extraction and audit behind
small interfaces owned by the gateway application. Centralize dependency versions
and retain Cargo.lock. Axum/Tokio supply maintained HTTP/async primitives;
jsonschema supplies canonical validation; sha2/subtle supply mature digest and
constant-time comparison; tracing supplies structured telemetry. No plugin code,
database, HTTP egress or general scripting engine is introduced.

An administrator mounts an immutable JSON configuration and separate credential
file. High-entropy bearer tokens are stored as SHA-256 digests with exact tenant,
user, agent, optional device and expiration bindings. Identity never comes from
request bodies or forwarded identity headers. Tokens are organization runtime
credentials, independent of Marketplace/deployment/bundle/permit signing keys.
TLS terminates at a trusted restricted ingress; local development binds loopback.
This adapter is explicitly static authentication, not an OIDC implementation.

Static rules contain exact identity/tool/action/resource matches. Emergency deny
and any matching BLOCK precede ALLOW; no match blocks. Policy expiry blocks and
removes readiness. ASK from a future evaluator blocks until approval exists.
Resource identity comes only from a trusted extractor binding; unknown tools,
missing arguments and traversal-like locators reject. Authorization responses
are decisions only: no permits or execution authority is manufactured.

The MCP skeleton implements stateless 2026-07-28 request framing and mirrored
header validation, ping and empty discovery. It never advertises executable tools;
tools/call traverses authorization and returns explicit unsupported execution on
ALLOW. Legacy sessions, signed bundle loading, credential brokering, forwarding
and permit issuance are outside this module and have no permissive fallback.

Admission is bounded per replica (connections, concurrency, fixed-window request
rate, header/body bytes, time and audit queue). Replicas share no correctness
state; rate limits are capacity protection per replica, not organization quotas.
Audit must acknowledge a sanitized event before returning ALLOW; sink failure or
queue saturation rejects. Stdout acknowledgement proves local write/flush only;
durable centralized audit needs a later adapter. Shutdown removes readiness,
stops new work and drains requests within a deadline.

Consequences: static credentials/config require rolling restart for changes;
unsigned network bundles are never accepted. There is no prior stable API release.
The added closed authorization request/audit contracts introduce new types without
altering frozen v1 definitions. Their fixtures and generated bindings are checked
across all four languages.

Protocol reference: [MCP 2026-07-28 Streamable HTTP](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http).
Framework references: [Axum](https://docs.rs/axum/0.8.9/axum/) and
[jsonschema](https://docs.rs/jsonschema/0.58.4/jsonschema/).
