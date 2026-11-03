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


## Prepared HTTP transport binding

Updated brokers always request the optional `httpRequestDigest` claim before
injecting the service-permit header. The signed claim and immutable effect
binding cover the version-1 prepared request fingerprint. A changed fingerprint
cannot reuse the same write nonce. Control still independently checks the
admitted identity, operation, resources, body and current authority; the HTTP
digest is an authenticated broker assertion, not independent route approval.

Upgrade Control, this Java library and each target integration before the broker.
Legacy unbound reads retain their existing checks; unbound writes are refused. A new bound permit passed to
the four-argument `Request` constructor is refused: use the five-argument
constructor with `ServicePermitVerifier.Transport`. Generate its profile data
from trusted routing configuration and its URI/method/headers from the accepted
HTTP request, preserving encoded path/query and repeated header order. Use the
configured public destination origin, not a forwarded-host header or internal
load-balancer origin. Pass the exact buffered body. Do not copy a submitted
digest, manifest or permit claim into the transport facts.

`HttpRequestFingerprint.Profile` includes pinned profile ID/digest, route ID,
auth declaration and excluded credential header names. The fixed service auth
declaration is `toolgate-service-permit:olo-memory` or
`toolgate-service-permit:olo-rag`, excluding only `toolgate-service-permit`.
All other semantic headers, including Accept and recipient headers, must be
observed and bound. The broker sends normalized content type and query; preserve
their actual wire values. A proxy changing them causes safe refusal. Verifier
transport maps/lists and body bytes are defensively copied.

The target verifier recomputes the digest locally before online consumption;
missing facts and transport changes consume no authority. Control also compares
the target-observed digest to the signed value. The optional contract additions
change generated Java constructor signatures and require coordinated recompilation.
They do not implement generalized effect APIs, exact consent or target deduplication.


### Independent Control profile and route checks

Leased v2 issuance now also requires `httpRequestManifest` and
`invocationPermit`. The updated broker submits both from its immutable prepared
request and original admitted dispatch; workers never receive that authority.
Control verifies the signed admission and live execution lease, loads the profile
by the signed digest, validates route/destination/metadata and admitted body hash,
and recomputes the submitted request fingerprint. The short admission window may
have expired after consumption; current execution authority must still be live.

A v2 host cannot omit all binding fields to fall back to legacy issuance.
Digest-only bound brokers from the preceding development checkpoint must upgrade
with Control. Unbound legacy issuance is available only for memory.read and rag.query without a v2 lease. Writes require the complete bound request and owner protocol.
No raw body or credential value belongs in the manifest. The original signed
permit and non-credential manifest are authority metadata: do not log their
contents or return them to package code.

Whole-segment path variables support a single bounded linear character class;
unsupported complex/embedded patterns fail closed. OLO endpoint paths must match
the admitted operation. Query names/values use canonical RFC 3986 encoding,
sorted by encoded names with repeated-name order preserved. Header names are
lowercase; only declared semantic headers and the implicit Accept default are
accepted. Broker and target continue to enforce byte limits.

These checks approve a declared route and bind its exact transport. They do not
supply the separate exact-consent slots or general effect-owner protocol.

### Actual memory/RAG listener integration

The consolidated apps live in olo-labs/olo-mono under olo-knowledge. Their shared
KnowledgeServer captures actual HTTP facts using read-only
TOOLGATE_TRANSPORT_BINDINGS_FILE configuration provisioned from the approved
deployment. See that component's README for the closed format and coordinated
SDK/profile rollout. Never copy trusted profile fields from request headers or
unverified JWT payloads.

A verifier request with transport facts explicitly requires httpRequestDigest.
An unbound legacy permit is refused before consumption. Other adapters using the
legacy four-argument request constructor may continue reads; writes must migrate to accepted-request facts and the bound claim protocol.

### Owner-bound write dispatch

Bound issuance also requires brokerInstanceId and handlerId. The trusted broker
creates one random in-memory instance ID per process and a fresh handler ID for
each route call. Control records them in the effect generation; another handler
or restarted process cannot obtain that generation's grant.

Before an OLO write contacts its target, the broker calls
POST /api/control/v1/access/service-permits/sending over direct device mTLS with
the signed service permit and both owner IDs. Control verifies the executor key,
current invocation authority, exact recorded grant and owner, then atomically
changes AUTHORIZED to SENDING. Only one concurrent caller succeeds. A refused or
lost response permits no target send or automatic replay. Target consumption
requires SENDING for bound writes. Reads retain their existing boundary.

Upgrade broker and Control together; generated Java issue constructors gain two
optional wire fields that are mandatory for bound issuance. The explicit
development loopback option never downgrades reserved OLO destinations to HTTP:
they still require verified HTTPS and client certificates.

This slice supplies no public release/reopen protocol, shared target claim store,
exact consent, general route grants or outcome finalization. A send-gate success
is permission for one send, not evidence that a target committed a business effect.

### Claim-token consumption and target outcomes

Bound OLO write grants now sign effectId and authorizationGeneration. Upgrade
target contracts and the service SDK before Control emits these fields; old
strict JSON readers cannot accept them. Drain short-lived in-flight grants during
the coordinated upgrade. Legacy one-shot consume retains its no-replay behavior.

For targets integrating the shared claim store, commit the unique local claim
first. Its owner posts ServicePermitClaimConsumption to the audience's
claims/consume endpoint with the verified request facts and its random 64-hex
claim token. Control checks the stored signed grant, exact request, current
authority and SENDING generation before its first atomic consumption. Repeats
return status; a different token receives CONSUMED_BY_OTHER_CLAIM. No reply is
a fresh execution capability. The target must own its local claim and win
beginApply before acting, and still check the original authorization deadline
immediately before business commit. Replaying status never extends that deadline.

POST claims/status accepts ServicePermitClaimReference. It returns only recorded
status after expiry or termination, without requiring the original signing key.
POST claims/outcome accepts ServicePermitTargetOutcome, using the same reference,
COMPLETED or FAILED and a result digest. The dedicated audience service identity,
tenant and recorded claim must match. Late outcomes append target evidence;
contradictory outcomes become CONFLICT. They cannot renew dispatch. Broker
observations do not settle a consumed effect.

These authenticated APIs live under
/api/control/v1/access/service-permits/{audience}/. Credentials and claim tokens
remain within the trusted target boundary. The actual memory/RAG adapters now
use verified-write SDK facts, durable local claims and this protocol. They commit
APPLYING before business actions and completion/outcome intent with SQL
publication. Startup requires the additive target schema and restricted runtime
permissions; writes have no one-shot fallback. Whole-operation finalization
remains conservative and separate.


## Legacy write upgrade

Control refuses unbound write issuance and consumption with HTTP 409 and the
typed detail code BOUND_SERVICE_EFFECT_REQUIRED. This also applies to correctly
signed, unexpired grants issued by an older Control. No nonce or effect ownership
is consumed by this refusal.

Upgrade the executor and target adapters together: reserve and consume a v2
invocation, construct the immutable prepared HTTP manifest from the admitted
profile, request the bound service permit with broker/handler ownership, cross
the SENDING gate once, and let the target independently verify accepted-request
facts before its durable claim. Do not retry an ambiguous legacy write to
complete the upgrade; reconcile its existing target outcome first.

Legacy memory.read and rag.query calls remain compatible when no v2 lease is
present. A v2 lease always requires its signed admission and prepared manifest,
including for reads. This migration grants no target deduplication guarantee.


## Current-source owned stack qualification

Build an isolated qualification image with
`python tools/security/knowledge_image.py`. Its report identifies the image,
known parent revision and exact runtime-source content. The builder compiles
Control and the native Gateway/Tool Host from those inputs and rechecks their
source identity before emitting evidence. This supports testing a complete
uncommitted stage without an intermediate delivery commit.

Pass the resulting image name to `tools/security/knowledge.py --full-stack
--stack-image IMAGE --knowledge-source PATH --gradle GRADLE_8_12`. Select the
upgraded knowledge component with trusted transport bindings. The gate requires
actual component PostgreSQL/TLS/vector results, verifies the installed SDK
artifacts, then compares committed memory/RAG claims and delivered target
outcomes to actual Control records. It accepts the exact owned content identity
or the legacy clean-revision image path; stale source and unowned images fail.
Image preparation alone is not stack acceptance.
