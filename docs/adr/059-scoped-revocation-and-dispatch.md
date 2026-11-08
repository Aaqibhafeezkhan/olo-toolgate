<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 059: Scoped revocation, delivery and authenticated dispatch

**Status:** In implementation for S22; no stage or phase acceptance.

This implements ADR 017 and the preserved execution proposal's phase-3 exit
requirements. Events, supersessions and archives are insert-only in fresh
current schemas. Unsupported existing databases are refused without conversion.
No compatibility wire formats, migration chains or legacy runtime paths ship.

## Binding and ordering

Every signed kill and recovery binds its canonical tenant UUID and committed
authorization epoch in addition to issuer/counter, scope and issue time. The
original unshipped event shapes omitted tenant binding; accepting such events
would permit cross-tenant replay. They are replaced, with no compatibility parser.
Fleet custody signs the domain-separated canonical event and push documents.
Operator approvals remain a separate 2-of-3 key domain and may only add kills.

STOP_NEW blocks new admissions and cancels matching queued work. TERMINATE also
ends matching executing authority and requests healthy-host teardown. Existing
completed business effects remain recorded. Unaffected work and running work
under STOP_NEW must not be accidentally terminated by a scope-specific epoch
bump. Only a contiguous series of committed kill/recovery epochs may bridge an
older invocation epoch; intervening directory/credential authority changes still
invalidate it. Current policy, consent, lease and target review remain mandatory.

The current online PermitConsumed response carries the accepted authorization
epoch and immutable KillExecutionScope from the per-attempt journal in the same
T5 transaction. The host compares package/runtime facts with verified content,
requires the exact signed nonce and a live deadline-clipped lease, and persists
admission uncertainty before contacting Control. Missing facts remain unknown and
match scoped kills conservatively; empty pool/image means known not applicable.
Placement is never reconstructed from a mutable host registration after admission.

## Delivery and dispatch

A certificate-authenticated outbound long-poll delivers signed current kill
state without holding a tenant transaction while waiting. Heartbeats retain the
degraded path; an independent monotonic watchdog retains the partitioned path.
The bounded long-poll handler uses a virtual thread so idle polls do not consume
shared REST worker threads ([Quarkus execution guidance](https://quarkus.io/guides/virtual-threads/)).
Acknowledgements bind verified component identity and advance monotonically.
Replayed pushes cannot remove retained events. Only an authenticated current
complete fetch may replace state, at or above the persisted epoch floor. The
fixed-origin authenticated long-poll also returns a complete snapshot; its
replacement permits convergence after archive. Relayed documents cannot use
this exception or introduce trust keys. The private host journal is atomically persisted
before acknowledgement or notification. Restored state supplies a floor, not
startup readiness: a fresh fixed-origin authenticated fetch is mandatory.
Persistence failure ends supervision and readiness until repair/restart.
Production HTTPS execution subscribes to matching TERMINATE updates independently
of heartbeat and monotonic lease expiry; STOP_NEW preserves already accepted work.

Remote inbound dispatch requires mutual TLS with configured tenant-specific
Gateway authority; socket-accepted peer facts cannot come from headers. Permit
consumption stays online and distinct from TLS authentication. Development
loopback fixtures must be explicit and cannot become a remote plaintext path.
Break-glass uses a separately authenticated operator listener and offline
approvals; it supplies no recovery or execution authority.

## Independent review

Normal kills and recoveries use retained proposals and two distinct currently
authorized human reviewers, neither the requester. There is no super-admin
self-review shortcut. A rejection closes a proposal; accepted changes and their
closure commit with the event and epoch bump. Current permissions and proposal
expiry are rechecked at application. Emergency 2-of-3 operator approvals use the
separate certificate listener instead of weakening human review. Its fixed tenant
and accepted client-only operator-CA certificate are independent of request
headers and offline approval keys. Current approval claims explicitly include
issue time as well as expiry, preventing indefinite pre-signed windows. A
tenant-wide immutable one-use request tombstone commits with the event; replay
through a different peer cannot allocate a second kill. The dedicated operator
OpenAPI document describes this listener separately from tenant ingress.

## Native execution and current wire separation

Tool Host consumption accepts only PermitConsumption at permits/consume. Native
endpoint consumption has its own native-permits/consume route and mandatory
NativePermitConsumed response, including the frozen scope and live lease. There
is no field-probing fallback between the two request shapes. Missing executing
leases deny sensitive authority; metadata evidence does not renew them.

Native clients retain the signed kill floor in fresh journal format 4, refuse
unsupported journals intact and require a live authenticated fetch after restart.
The main owner persists before publishing to local/relayed runtime supervisors or
acknowledging CHECK_IN delivery. Native authority combines this degraded delivery
path with independent monotonic heartbeat expiry and runtime cleanup. An absent
deadline or unsupported supervision port cannot grant managed execution.
Connected native delivery timing is not established by these component checks.

## Inventory, retention and operator presentation

Accepted Gateway catalogs register their verified relay actor identity, filter
matching killed tools per eligible device and return the authorization epoch
of the same transaction. Gateway validates the closed catalog and its single
canonical epoch header before acknowledging POLL with its own credentials.
Neither caller-supplied component ids nor future epochs are accepted.

The retained status view joins registered Tool Hosts, other endpoint devices
and observed Gateways with monotonic acknowledgements. Management reads,
acknowledgements and a background sweep archive superseded kill/recovery pairs
after every registered component acknowledges the recovery epoch or thirty
days pass. Active kills are never archived. Archived signed rows, counters and
audit remain immutable. SQL binds each supersession to its later recovery's
explicit supersedes list; acknowledgements cannot change identity or move
epoch/time backwards.

The console presents receipt separately from execution-stop evidence and
requires explicit confirmation of the exact proposal revision for each human
review. Stale state clears review confirmation. Emergency private keys never
enter the console or tenant bearer API.

## Required evidence

Run SQLite/PostgreSQL runtime-role storage and authority tests, signed-event
tenant/type/key/replay/reordering and full-fetch vectors, 2-of-3 operator and
independent recovery review cases, actual mTLS dispatch refusals, and joined
kill/partition/crash/queued-work tests. Measure connected, degraded, partitioned
and supervisor-dead behavior on the declared healthy-host tier. Compromised root
hosts retain the explicit unbounded unmediated-risk limitation.

S22 earns no weight until its complete original scope, including phase-3
aggregation/conformance, passes. Local measurements do not establish arbitrary
production topology guarantees. The live status document records executed
evidence, missing cases and the unchanged weighted baseline.

Pending admissions are registered before online consumption. A verified termination
after their captured floor is retained per active handle through recovery and
full-fetch retention. The validated consume reply refines the frozen scope and
accepted epoch once; later metadata cannot renew that execution. This avoids both
STOP_NEW over-cancellation on delayed replies and revival when a combined
kill/recovery snapshot arrives before the worker polls its notification.

Gateway relays read signed state only through their authenticated fixed Control
origin. Dedicated mTLS routes forward the signed envelope without keys. Hosts
verify against direct-Control trust, persist a merge, notify cancellation and
acknowledge PUSH independently; relays cannot perform full-history replacement.
