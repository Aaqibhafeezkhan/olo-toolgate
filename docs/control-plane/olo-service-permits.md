# OLO downstream service authorization

Fresh installations only. Initialize empty Control stores with V1. There is no schema upgrade path, old-execution
reader, or compatibility mode. This bridge is M4; the memory/RAG application services remain M6.

## Installation contract

- Run services in a dedicated `olo-knowledge` namespace, labelled `app.kubernetes.io/part-of: olo-knowledge`.
  Apply [network-policy.yaml](../../deploy/olo-services/network-policy.yaml) using a NetworkPolicy-enforcing CNI.
  Only `toolgate` namespace pods labelled `app.kubernetes.io/component: tool-host` reach service TCP 8443. OLO backend
  and worker pods do not receive these labels or network access. Configure service egress separately for Control,
  DNS and its own database; database credentials never go to the Tool Host or OLO.
- Configure each service's HTTPS listener to **require** client certificates issued by the Tool Host device CA.
  Get the peer from the accepted TLS session. Never accept identity from a proxy header or request certificate field.
- In the approved Tool Host service profile, reserve destination IDs `olo-memory` and `olo-rag` for these services.
  Use brokered HTTPS, a verified CA bundle/system CA, no package HTTP auth, and no pinned-only TLS configuration.
  The broker presents its own device certificate and obtains/injects `Toolgate-Service-Permit` after route checks.
  Package code cannot set that header or obtain the private key. It never receives the permit in broker responses.
- Package `@Authorization` declares the operation and locator template. The operation determines the option:
  `memory.read` and `rag.query` are bounded reads; `memory.append/delete/share/export` and
  `rag.ingest/update/delete/share` consume online. Long-term memory operations are absent from the first release.
  Use POST with the exact authorized JSON argument body. Transformations or extra body fields are refused by the
  service hash check. Endpoint/path/query resource IDs must also match the resources passed to the verifier.
- Give each service a Control-audience credential with only `toolgate-service-olo-memory` or
  `toolgate-service-olo-rag` for its own tenant. It can consume that audience's permits and read its tenant's epoch/key
  set; it cannot mint executor permits, issue user credentials, or change grants. Keep credentials outside workflow
  payloads. Multi-tenant deployments use a separate authenticated Control binding per tenant.

## Control endpoints

All paths are below `/api/control/v1/access`.

| Endpoint | Authentication | Effect |
|---|---|---|
| `POST /service-permits/issue` | Direct device mTLS plus admitted invocation's fencing token | Sign scope taken exclusively from the running invocation |
| `POST /service-permits/{audience}/consume` | Dedicated service role for the named audience and tenant | Verify exact request evidence, current authority and single-use nonce |
| `GET /service-permits/{audience}/epoch` | Same service role | Coalesced tenant revocation feed snapshot |
| `GET /service-permits/{audience}/jwks` | Same service role | Current purpose-bound effect public key |
| `POST /invocations/cancel` | Relay Gateway role; verified caller context | Persist cancellation intent, even before first submit |

The Gateway exposes cancellation as MCP extension `olo/invocations/cancel`, with `operationId` equal to the original
**string** tools/call JSON-RPC ID. Its authenticated identity derives exactly the same invocation key; client fields
cannot choose another user's context. OLO only uses string logical-operation IDs. Executing cancellation is recorded
`OUTCOME_UNKNOWN`, never a claim that an external effect was undone. Terminal results stay terminal.

Service JWS headers fix `alg=RS256`, `typ=olo-service-permit+jws` and a discovered `kid`. Claims bind issuer, audience,
tenant, subject, optional delegated actor, mode, operation, resources, exact body SHA-256, executor public-key SHA-256,
invocation, execution fence, nonce, authorization epoch and Unix-millisecond issue/expiry times. They cannot be used as
executor admission permits. Claims do not contain authentication tokens or body content.

## Service transaction boundary

Depend on `io.github.olo-labs:toolgate-service-authorization` (published with `publishJavaSdkLocally` during local
builds). `ControlServiceAuthorization` uses a fixed verified HTTPS Control origin, a credential supplier, the expected
issuer, audience and tenant. It refreshes its authenticated epoch/JWKS snapshot within five seconds and fails closed on
an unavailable refresh. The verifier refuses a read epoch snapshot older than ten seconds.

For every endpoint, construct `ServicePermitVerifier.Request` from the actual request bytes, endpoint operation,
selected tenant and **every** resource touched. Pass the accepted mTLS session to `authorize`. Verify ownership,
membership and sharing independently in the service's storage transaction; ToolGate authorization does not replace it.
For a write, call authorization within that transaction, perform the scoped change, call
`authorization.checkCommitDeadline()` immediately before commit, and roll back on any refusal. Bound transaction and
commit timeouts to the permit deadline. For reads, authorize before loading and again before releasing the result;
expire/discard data rather than release it under stale evidence. Service ACLs and release checks are M6/M7 work.

Online consumption is intentionally irreversible. If Control consumed the nonce but storage did not commit (including
an ambiguous network response), do not mint another write capability and retry the effect. Reconcile the storage's
transaction/idempotency ledger by invocation ID, then record its known outcome in ToolGate. Each committed indexing
chunk is a separate logical operation; only a fully authorized version is published. This avoids a false claim of an
atomic transaction spanning Control and a service database. A repeated mint returns the original unconsumed write
permit with its original expiry; a consumed, expired or differently fenced write cannot obtain a new capability.

## Revocation, deadlines and rotation

All service permits live at most 15 seconds. A user permit is also capped by the **persisted verified credential
expiry** of the original submission or a verified renewal; a running invocation cannot extend that deadline itself.
Freshness checks reject expired authority even when the invocation/approval lifecycle remains open. This prevents
credential -> executor permit -> service permit lifetime chaining. Queued and approval-waiting invocations receive no
service permit. Each dispatch/chunk is authorized anew. Epoch changes reject outstanding writes online and stop reads
on the next snapshot; offline reads still expire within 15 seconds. Storage commit/release timeouts are required to
stay inside the accepted 30-second bound. Production measurement remains M8.

Rotate the dedicated effect signing key/id through normal Control custody. Services refresh the authenticated key set;
unknown/retired kids fail closed. Rotation invalidates outstanding write permits rather than creating a new nonce under
new authority. Reconcile an ambiguous write before continuing. User credential renewal extends only the non-secret
stored deadline; it changes neither the logical-operation key nor the request fingerprint. Device key changes invalidate
a permit bound to the previous key. Service credentials come from the supplier on every Control request.

## Qualification

`ServicePermitBridgeTest` covers trusted identity/resource propagation, scope/body/executor/audience mismatch, repeated
mint and nonce consumption, cancellation before submission, cancellation during execution, approval/queue refusal,
revocation before the effect and credential-deadline chaining. `ServicePermitTlsQualificationTest` crosses a real
mutually authenticated TLS socket into a separate SQLite storage transaction. `ServicePermitVerifierTest` covers
forged requests, wrong purpose/kid, absent mTLS, stale epochs, expiry and delayed commits. Gateway and Tool Host suites
cover authenticated ingress, broker isolation and package header forgery. Fresh PostgreSQL initialization and runtime
row-security tests include the new authority and cancellation tables.

M6 must run the same cases against the actual memory/RAG services and stores. M8 measures the real-stack timing and
result-release behavior. Passing this protocol qualification does not claim that those application services exist.

## Deploying private application services

Package deployment accepts operator-controlled `privateRanges` and `destinationTls` in addition to host globals.
A range must name a declared destination host, contain only that host's declared ports, and fit wholly within
RFC 1918, CGNAT or IPv6 ULA space. Public, loopback, link-local/metadata and wider containing ranges are refused.
The broker still checks every resolved address at connect time. An approval cannot override its forbidden-address rules.

`destinationTls` is keyed by the declared destination ID. Each value is `{caBundle:"<inline PEM>",spkiPins:[]}`
for a private CA; bundles are bounded to 64 KiB and parsed as X.509 certificates before deployment. This is not a
filesystem path or key reference. Unknown destination IDs and malformed bundles are refused. `olo-memory` and
`olo-rag` cannot use pin-only TLS: their mTLS transport retains normal CA and hostname verification.
The settings are included in the canonical, revisioned service-profile digest pinned by the executor permit.
Only an administrator holding package.deploy may set them; package authors cannot approve their own private ranges.

For example, deploy `olo.memory` to `ServiceHost=olo-memory`, approve only the actual private subnet for that host
on TCP 8443, and set `destinationTls["olo-memory"]` to its server CA PEM. Do the same separately for `olo-rag`.
Never grant `0.0.0.0/0`, use a TLS verification bypass, or install a database credential in an executor.
