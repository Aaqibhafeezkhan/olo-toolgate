<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 023: Server-directed device presence and reenrollment

**Status:** Accepted on merge of the PR that adds this file

## Context

Deleting a device's directory record leaves a running client with its old local
identity. Protected check-in rejects it, and the client can stop communicating
without making the device available for a fresh approval. Local revocation and
expired approval must also not prevent the server from reconciling device state.

## Decision

Clients send a presence ping before every check-in cycle, including while waiting
for enrollment, disabled, deapproved or revoked. The ping carries the stable
device ID, platform, client version, capabilities and a signed PKCS#10 CSR over
direct HTTPS, together with the current OS UTC offset in `EndpointPresencePing`.
Control validates the CSR and binds the ID to its public key.

Control returns the current state, authoritative report sequence, next interval
and one action: `ENROLL`, `CHECK_IN` or `WAIT`. A ping conveys no execution grant,
certificate or protected work. Protected operations retain their existing
certificate, approval, enablement and policy checks.

Deleting a directory device clears its connection approval, identity metadata,
latest report and old enrollment codes while preserving its key binding and
report sequence. The next live-client ping requests fresh enrollment with that
same ID and key. Reenrollment always requires administrator approval, including
when initial enrollment is configured for automatic approval. A deleted device's
protected request returns HTTP 410 so deletion between ping and check-in follows
the same recovery path.

Disabled devices and devices whose connection approval expired remain listed and
receive `WAIT`. Expired approval is shown as **Approval required**. Explicit
revocation remains durable and also receives `WAIT`; a ping cannot restore access.
Clients retry expired enrollment challenges and follow server directives while
retaining their original private key.

Revoked devices ping at the next X:00:00 in their local time, then hourly. Control
calculates the next interval from the supplied offset; the client recalculates the
local boundary before sleeping, including after transport failures. Admin changes
are stored on the server and do not push to or wake a disconnected client.

An explicit **Request approval again** admin action or OS-authenticated
`request-approval` client command may reopen a revoked identity for review. The
admin action leaves it awaiting a fresh CSR at its next scheduled ping; the local
command requests a fresh challenge immediately. Both invalidate old enrollment
codes, preserve the ID/key and report sequence, and require manual approval even
when initial enrollment is automatic. Ordinary reapproval cannot bypass this
review. This amends ADR 007's unconditional terminal revocation behavior.

## Alternatives Considered

- Infer recovery solely from failed authenticated check-ins. This cannot reconcile
  clients without a usable certificate and leaves local terminal states stranded.
- Automatically restore a deleted device's old approval. This would bypass the
  administrator's decision to remove the device.
- Assign a new device ID on reconnect. This loses the bound device identity and
  makes repeated discovery harder to manage.

## Security Impact

The anonymous presence endpoint verifies a signed CSR and rejects a device ID
claimed with a different key. Presence does not authorize execution or skip
approval. Deletion invalidates prior enrollment codes, and the server preserves
the report sequence so reenrollment cannot replay an accepted report. Existing
revocation and disabled-device checks remain effective until an explicit new
request receives fresh approval. Admin reopening is authorized, revision checked,
idempotent and audited. Local reopening proves possession of the existing key and
never receives an automatically approved certificate.

## Operational Impact

The upgraded client makes one additional HTTPS request per check-in cycle. Control
sets the interval. Revoked devices wait until the next local hour even after a
transport failure; other states retain bounded retry backoff. Live
deleted devices reappear in the pending queue and require administrator approval
and configuration. No database migration or additional service is required.

## Compatibility Impact

The v1 contract adds a new endpoint and new directive models without changing
existing wire models. Java, Rust, TypeScript and PHP bindings are regenerated.
Deploy the server before the upgraded client: older servers do not implement
presence ping, and older clients do not implement automatic reenrollment after
deletion or explicit approval requests after revocation. Updated clients understand
HTTP 410 from protected operations. The existing local `ENROLL` operation also
handles explicit approval requests, so IPC wire models stay unchanged.

## Consequences

Control owns recovery decisions in every client state. Stable key-bound IDs make
live devices discoverable while fresh approval remains mandatory after deletion.
Continuous presence increases request and CSR-validation load; operators should
include it when choosing the existing client check-in interval.

## Validation

- PostgreSQL and SQLite integration tests cover deletion, stale-code rejection,
  key mismatch, pending visibility, fresh approval, disablement, expired approval
  and explicit revocation.
- Client tests cover continued pings while waiting and revoked, durable
  reenrollment, report-sequence recovery when deletion races with check-in, and
  retry after enrollment-challenge expiry.
- Server and client scheduling tests cover whole-hour, half-hour and quarter-hour
  offsets; integration tests cover client and admin reopening, stale revisions,
  admin replay and mandatory fresh approval despite initial automatic approval.
- Console tests verify the approval-required label and pending device actions.
- Contract fixtures and generated-binding checks verify the shared ping protocol.
