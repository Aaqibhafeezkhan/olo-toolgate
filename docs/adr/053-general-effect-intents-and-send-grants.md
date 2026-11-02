<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 053: General effect intents and single-owner send grants

**Status:** Accepted; generic HTTP increment ready for the user-requested checkpoint commit. S18 remains Partial.

## Problem

Admission and a renewable lease do not authorize every later mediated effect.
The existing independently validated owner protocol covers OLO memory/RAG.
Other HTTP routes and native MCP tools/call must acquire fresh authority at their
effect boundary. Legacy unbound service writes must not bypass that boundary.

## Decision

Publish operator-reviewed per-route effect policies in immutable Control-owned
service profiles. Package authors and worker frames cannot supply review stamps.
Classify READ, WRITE, DESTRUCTIVE and FINANCIAL explicitly; flag unfenced critical
routes as a reviewed security delta. Missing/ambiguous policies fail closed.
Keep the existing independently enforced OLO service path and its target
consumption ordering; a generic grant cannot downgrade a reserved OLO target.

An intent blueprint binds path/query/noncredential headers and the full body to
issuer-signed admitted arguments and reviewed constants. Canonical JSON body
templates use closed argument/context references with bounded depth/size.
Control independently reconstructs the intent; a worker cannot change recipient,
amount/body, method, query or semantic headers by proposing a different digest.
Stored policy strings must already be NFC because profile digests use NFC;
admitted argument values preserve their exact non-NFC JSON meaning. Unsupported
dynamic/unprovable intent sources are refused, not treated as approved.

In one tenant writer transaction verify the client certificate, original signed
admission, immutable package/profile, active attempt/fence/lease, current grants/
session/epoch/deadline and reviewed intent. Persist the intent and reserve its
effect generation/unique authNonce before returning a separately typed signed
grant. A stable operation/route identity cannot acquire a different request
binding; duplicates, old owners and sent/ambiguous generations gain no new send.

Require a second owner-bound AUTHORIZED-to-SENDING CAS immediately before the
transport. A lost/false/error response burns the local handler; never retry the
HTTP request. READ is checked online too; no stale epoch cache is introduced.
Unfenced grants live at most two seconds and never extend the original deadline
or credential/lease lifetime. Exact consent remains an independent authority
source: a consent-required route is refused until S19 provides its exact slot.
Safe resend/equivalence/target-deduplication sources remain S20 requirements.

The supervisor enforces expiry with a monotonic budget captured before requesting
the grant. Use a one-shot private Unix-socket transport relay to the single
approved destination, checking every outbound write and all resolved addresses.
Reqwest retains HTTPS/SNI/chain/leaf-pin validation over that local transport,
fresh HTTP/1.1 and no redirect/retry behavior. Slow DNS/TLS cannot extend the send
window. The socket directory is private to the supervisor and never appears in
worker/broker IPC or mounts; it is not a general proxy API. Cancellation/deadline
closes the relay. Response delivery may continue after a complete timely send.

Native MCP tools/call uses an independently reconstructed synthetic intent from
the approved proxy document and original arguments; metadata discovery is not a
business-call bypass. Protocol-owned correlation/session headers are explicit,
bounded, bound into the grant and cannot select another target. Unbound legacy
service writes are refused; retained historical evidence is still readable.

Broker response/status reports are observations. An unfenced 200 cannot invent
an authoritative target receipt, safe resend guarantee or successful roadmap
acceptance. Preserve existing all-attempt finalization and reconciliation rules.

## Delivery and evidence

Repeat focused schema/binding, pure intent, SQL transaction/race and transport
changes without intermediate commits. Then execute cross-language contracts,
SQLite/PostgreSQL/Java checks, real expired/refused/allowed wire witnesses,
ordinary HTTP and native MCP paths, current-authority changes, duplicate/owner/
lost-response cases and current-source OCI/owned stack gates. Update operator/
review UI and authoritative progress in the one complete-stage commit.
Until those complete, S18 stays Partial and earns no planning weight.
