<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Emergency kill switch

S22 is in implementation under [ADR 058](../adr/058-scoped-revocation-and-dispatch.md).
The [live progress record](../security/zero-trust-execution-status.md) distinguishes
executed checks from remaining joined execution, conformance and timing gates.
This guide describes the current contracts; it does not accept S22 or phase 3.
ADR 017 and the preserved execution plan remain the requirements baseline.

Current public contracts are [kill schemas](../../packages/contracts/schemas/v2/kill.schema.json),
[tenant Control API](../../packages/contracts/openapi/control-v1.yaml) and the
[separate operator API](../../packages/contracts/openapi/kill-operator-v1.yaml).
All Control paths below have prefix /api/control/v1 except the operator listener.

## Scope and execution ordering

Every signed event binds its canonical tenant UUID, committed authorization
epoch, issuer/counter, issue time and scope. Effective kills are kills without a
committed supersession. TERMINATE wins over STOP_NEW when scopes overlap.

| Scope | Value |
|---|---|
| PACKAGE | Package id |
| PACKAGE_DIGEST | sha256 digest |
| PUBLISHER | Verified package publisher id |
| TOOL | Tool id |
| POOL | Execution pool id |
| RUNTIME_IMAGE | Runtime image digest |
| TENANT | Absent |

Unknown scope facts match conservatively. Empty pool/image denotes known not
applicable. Permit consumption freezes attempt scope; a later mutable host
registration cannot change the facts used to supervise accepted work.

STOP_NEW refuses matching new work and cancels queued work. It preserves
already consumed running work. TERMINATE additionally ends matching executing
authority and asks healthy executors to tear down owned runtimes. Potentially
sent effects retain OUTCOME_UNKNOWN; recovery cannot restart cancelled work or
erase completed effects. Admission, secret/effect authority and heartbeat still
require current policy, consent, fence and live lease. Only contiguous
kill/recovery epochs can bridge an otherwise current invocation's older epoch;
unrelated authority changes still revoke it.

## Normal human workflow

The console's Kill switch page shows signed current state, retained/archive
counts and registered component acknowledgement epochs, paths and times.
Refresh obtains current state; an acknowledgement is evidence of receipt, not
proof that an arbitrary compromised host stopped.

1. POST kill-events with KillRequest or kill-events/recoveries with
   RecoveryRequest and an Idempotency-Key. This creates a retained proposal,
   not an immediately applied event.
2. GET kill-events/proposals or kill-events/proposals/{id} to inspect the exact
   request, requester, expiry and current revision.
3. Two distinct currently authorized humans, neither the requester, each POST
   kill-events/proposals/{id}/decisions with revision, approve and their own
   Idempotency-Key. Recovery reviewers additionally require kill.recover.
4. A rejection closes the proposal. Two valid approvals apply it atomically.
   Permissions, expiry and epoch are rechecked at application. A stale revision
   requires a fresh inspection and confirmation.

Read requires kill.read, creation kill.create, review kill.approve and recovery
kill.recover. The console hides or disables unauthorized actions; Control
independently enforces them. A Super Admin label does not bypass separation.
Quickstart must provision distinct actual humans and permissions.

Event signing, epoch bump, stored-work cancellation, review closure, audit and
idempotent response commit together. An uncertain response must be resolved by
reading the retained proposal/event, rather than issuing another logical change.

## Signing and authenticated delivery

Control uses its external fleet signing custody. RS256 protected headers contain
only alg, typ and kid. Event signatures use toolgate-kill-event+jws; complete
state signatures use toolgate-kill-push+jws. Attached payloads must equal the JCS
bytes of the received document without signature. Tenant/type/key mismatches,
duplicate JSON members, malformed encodings and counter/id forks are refused.
Current public trust is bounded to four distinct strong RSA keys; offline
operator keys are a separate domain.

| Consumer | Direct authenticated path | Acknowledgement |
|---|---|---|
| Tool Host | Device mTLS: GET tool-hosts/kills/keys and tool-hosts/kills; bounded tool-hosts/kills/poll | POST tool-hosts/kills/acknowledgements, POLL |
| Native endpoint | Device mTLS: GET endpoints/kills/keys and endpoints/kills during check-in | POST endpoints/kills/acknowledgements, CHECK_IN |
| Gateway | Verified relay identity: POST mcp/catalog, including canonical Toolgate-Authorization-Epoch response header | POST gateways/kills/acknowledgements, POLL |

Host long-poll holds no database connection while waiting; production hosts use
a five-second poll inside the transport deadline. A fixed-origin authenticated
poll returns complete state and therefore follows the full-fetch replacement
rule. Untrusted relays cannot introduce keys or replace state. Native delivery
currently uses degraded check-in, not a qualified connected native SLO.
Gateway catalogs hide matching killed tools before discovery and acknowledge
only a validated current response. Failed acknowledgements do not grant new
execution authority; every invocation still goes through online Control.

Host and native journals persist the signed state and epoch floor before
publishing to supervisors or acknowledging. Restart requires a live direct
fetch; restored disk state alone is not readiness. Persistence failure ends
authority until repair/restart. Unsupported journals are refused intact.
Independent monotonic lease expiry continues during delivery failure; unavailable
or hung renewals cannot extend a lease. TERMINATE wakes healthy runtime
supervision independently of heartbeat. Native consumed authorization requires
both a deadline and supervision support.

Inbound Gateway dispatch uses separately configured mutual TLS with exact
tenant-specific client and server leaf pins. Socket peer facts cannot come from
headers. Without inbound TLS, production hosts are outbound-only. Plaintext
loopback dispatch requires the explicit developmentDispatchHttp option.

## Emergency operator listener

POST /break-glass/kills exists only on the separate operator mTLS listener.
It requires the configured tenant's client-only operator-CA certificate and
two or three distinct signatures from exactly three externally configured RSA
operator public keys. No administrative bearer identity substitutes for either.
Approval claims bind tenantId, requestId, scope, mode, reason, issuedAtUnixMs and
notAfterUnixMs; their window is at most ten minutes. The protected type is
toolgate-breakglass-approval+jws. Tenant-wide immutable request tombstones
prevent another peer from replaying the same request into another event.
Emergency approvals can add kills only; all recovery uses the human workflow.

Configure TOOLGATE_BREAKGLASS_PORT, HOST, TENANT_ID, CERTIFICATE_PATH,
PRIVATE_KEY_PATH, CLIENT_CA_PATH and OPERATOR_KEYS_PATH through corresponding
toolgate.control.breakglass settings. An absent port disables the listener.
Enabling requires external fleet custody and a dedicated operator CA; device-CA
reuse is refused. Use one tenant's operator authority per listener/deployment.
Toolgate-Tenant-ID may assert this tenant, never select a different one.

The listener bounds bodies to 128 KiB, authenticated concurrency to 32, its
dedicated worker pool to 16 and TLS/request deadlines to five seconds. These
bounds do not establish production overload or revocation timing guarantees.
After a lost reply inspect retained state using the same logical request id.

## Package suspension

Package, exact-digest, publisher and tenant kills project matching uploaded
versions as SUSPENDED in current package listings and repeat-import responses.
The immutable uploaded lifecycle state is preserved. Recovery restores that state
only after every matching effective kill is resolved; resolving one overlapping
kill does not unsuspend a still-covered version. Tool/pool/runtime-specific
restrictions continue to apply at catalog placement and execution boundaries.

## Retention and fresh storage

A superseded kill and its applied recovery leave the delivery set when every
registered tenant component acknowledges at least the recovery epoch, or thirty
days after recovery, whichever occurs first. Active kills never expire.
Registered inventory includes Tool Hosts, other endpoint devices and Gateway
relay identities observed through accepted catalogs. Missing/offline consumers
hold back acknowledgement-based archive until the time bound.

Maintenance runs on acknowledgements, management state/status reads and a
background tenant sweep (first after sixty seconds, then hourly). Archive rows
are insert-only; original signed events, counters, supersessions and audit remain.
Archived pairs leave complete snapshots together. A component that missed an
archived recovery converges on its next direct authenticated complete fetch.

Only a direct authenticated complete fetch can replace retained client state,
and only at or above its persisted epoch floor. Relayed signed events merge
without deleting retained facts. Old snapshots cannot remove a newer kill.

Fresh V1 initialization contains append-only events, supersessions, archive,
proposal/review/closure records, emergency tombstones, Gateway registrations and
immutable attempt scopes. A supersession must name a kill in its later recovery
document. Acknowledgement identity is immutable; epoch and observation time
cannot decrease. PostgreSQL runtime-role/RLS and SQLite triggers enforce these
storage rules. Existing stores receive no migration, conversion or backfill;
unsupported schemas and formats are refused without deletion.

Committed events, review decisions, acknowledgements, archive actions and
emergency attempts have audit records. Component receipt and healthy teardown
are distinct evidence. Full joined delivery, partition/crash, aggregation,
connectivity-class SLO and release scorecard gates remain required for S22.


## Signed Gateway relay

The dedicated toolgate-relay-gateway service principal may read
GET /api/control/v1/gateways/kills/keys and GET /api/control/v1/gateways/kills
through its configured authenticated Control origin. These calls register the
verified tenant/principal, not a request-supplied component identity. The Gateway
validates the current closed schemas and signatures before forwarding the signed
envelope concurrently to its bounded set of configured HTTPS mTLS Host routes.
A failed route does not block negative delivery to other Hosts. Delivery failures
and recovery have bounded transition logs.

The Host's POST /v1/kills endpoint is enabled only on its dedicated pinned-Gateway
mTLS listener. It verifies with previously fetched direct-Control keys and persists
a merge before notifying pending/active executions and sending an independent
PUSH receipt. A relay cannot install keys, perform full-history replacement or
clear a termination attached to an older execution. Normal POLL and Native
CHECK_IN receipts remain distinct from PUSH and from actual teardown evidence.
