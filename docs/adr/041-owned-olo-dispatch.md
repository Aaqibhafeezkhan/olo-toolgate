<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 041: Require an owner-bound single dispatch transition for OLO writes

**Status:** Accepted for the existing OLO service write path.

A broker process receives a random in-memory instance ID; each route handler
receives a fresh random handler ID. Bound issuance records both in the durable
effect generation. A different handler or restarted process cannot obtain that
generation's grant. Replaying issuance by the same owner is allowed only while
the generation remains AUTHORIZED.

Before the target send, the broker must call the direct-mTLS sending endpoint.
Control verifies current invocation authority, certificate key, signed service
grant and recorded owner, and atomically changes AUTHORIZED to SENDING. A lost
response does not permit replaying the dispatch: only one CAS succeeds.
A bound target write can consume only a SENDING generation. Legacy unbound
clients without a v2 execution lease retain their existing behavior.

No automatic target retries or connection reuse are added. A failure after the
send gate is uncertain; existing expiry/abandonment fences it. This is not the
generalized route/effect-key protocol, a release/reopen API, exact consent,
shared target claims or outcome finalization. Those remain separate work.
