<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 055: Exact effect consent and atomic slots

**Status:** Accepted; S19 delivered. Full phase acceptance remains outstanding.

## Decision

Financial and destructive effects always require explicit effect consent.
A deployment's unfenced-risk acknowledgement is not that consent. Other effect
classes require consent when their reviewed policy requests it. The exact-slot authority path gates all such effects,
including legacy financial/destructive reviews whose consent flag is false.
Historical admissions lacking a stored preview cannot acquire effect authority.

Build consent from independently reconstructed mapped business intent, the full
admitted argument digest, tool/action/class, exact resources, operation/run
identity and pinned profile. Reviewers see the actual mapped outbound business
values and explicit slot limits. A policy review or an ordinary argument-only
ASK record cannot silently become an exact-effect consent record.

Use immutable Control-owned preview/consent records and current authenticated
approval, with original credential/operation validity bounds. Consent slots bind
route, effect identity and mapped intent or exact request digest. Reserve a slot
and its effect generation in the same tenant writer transaction. Changed request
fields, another effect identity and exhausted slots are explicit refusals; failed
authorization rolls back both reservations. Reusing the same qualified effect
identity must not spend a second slot. S20 remains responsible for generalized
effect keys and safe resend; S18 currently allows one effect per route.

MCP JSON-RPC ownership and bounded session/protocol context are transport facts.
They remain signed into the complete request fingerprint; mapped business
consent binds the fixed approved upstream/tool and complete original arguments.
They cannot change the approved recipient or other business values.

Keep consent-required calls behind approval before dispatch. Recheck current
scope/reviewer authority, revocation, immutable intent, slot binding and expiry
at the effect boundary. Never extend the original consent window on approval
or recovery. Persist auditable bindings without exposing credentials to workers.

## Delivery

Start by closing mandatory high-risk consent paths, then add canonical contracts,
immutable preview/approval and atomic slot persistence, Control/runtime wiring,
review UI and focused mismatch/exhaustion/revocation/race tests. Follow with the
complete applicable stage gates and one code/tests/docs/progress commit.
Unsupported unprovable/dynamic intent remains refused; original full phase
acceptance and other authority sources remain separate roadmap requirements.


The supported execution scope is the authenticated logical operation. Declared
agentRunId/session correlation is telemetry and cannot create consent authority.
The current route-derived stable effect key admits at most one effect per
operation/route; generalized keys and safe resend remain S20. Unsupported dynamic
request planning stays refused. This does not accept broader C2/C3b/C8 claims.

Delivery uses exact ASK-style review with current group-scoped operation approval
authority and requester/agent-owner independence. The existing server-verified
Super Admin exception is preserved. A bearer-authenticated caller cannot approve
its own operation merely by naming a preview digest; application-signed
front-end user intents are not accepted by this workflow.
