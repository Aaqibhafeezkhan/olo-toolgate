# Endpoint Enforcement

## Purpose

Strong enforcement requires credential custody and/or OS controls. PATH wrappers alone are convenience, not a security boundary.

Enrollment lists require the same `toolgate-enroller` role and enabled, same-tenant
user as enrollment review and approval. Lists include at most 32 unexpired pending
requests and expose only review metadata, never private device codes, CSRs or
certificates. Decisions still bind the human approval to the reviewed fingerprint
and commit identity, deadline, audit and idempotency atomically.

Visitors with an own-device approval rule initially see no unclaimed requests.
Verifying the code supplied by their local client associates that pending request
with their signed-in account. It then appears as needing approval in Clients and
in the enrollment list, with its review code and fingerprint available to that
visitor. Other visitors cannot list or claim the associated request. Association
does not create an endpoint identity, issue a certificate or approve a connection;
the visitor must still compare the fingerprint and explicitly approve access.

New approvals use an absolute `connectionExpiresAtUnixMs` deadline (24 hours
when omitted by an older API caller), or explicit `unlimitedConnection: true`.
Enablement and connection approval are independent registry gates. Temporary
disablement, deapproval or deadline expiry returns HTTP 423 and retains identity;
permanent revocation remains irreversible. Check-ins, socket authentication and
tool access reject suspended approval. Certificates and renewal are capped at the
deadline, rounded down to X.509 second precision. Tool leases are also capped at
the finite deadline, and open job sockets recheck authorization every five seconds.
Unlimited approval keeps certificates short lived and supports exact-key recovery
after a long outage without changing the owner or grant.
