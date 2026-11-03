<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 054: Fresh effect authority for native MCP calls

**Status:** Accepted; S18 declared delivery implemented and qualified. Full phase/claim acceptance remains outstanding.

## Decision

Native MCP is a trusted compiled executor with a distinct transport, not an
untrusted Python worker or a qualified OCI containment tier. Its tools/call
must nevertheless cross the same current-authority, immutable intent and
single-owner SENDING boundary as generic HTTP effects.

Require explicit per-tool effect classification/risk/consent review in MCP
approval input. Copy that policy into the generated immutable proxy artifact
and stamp the approving Control actor/time into the pinned service profile.
Upstream annotations alone do not supply this authority. Old approvals without
a policy remain readable but cannot obtain native MCP effect grants; reapproval
is required. Consent-required policies remain refused until S19.

Control independently verifies the signed admission and argument preimage, the
current generated MCP package/deployment/profile and the exact approved tool.
It reconstructs tools/call from the pinned upstream name, all original admitted
arguments, and the owner handler ID used as the JSON-RPC ID. It binds the
canonical fixed upstream URL, exact body, credentials declaration and semantic
headers. Only MCP session/protocol headers may carry bounded protocol context;
they cannot select another target, tool, method or argument. They remain
included in the full signed request fingerprint. Ordinary generic policies
cannot opt into this exception.

Metadata initialize/list/initialized use fixed compiled protocol methods.
Production tools/call always uses fresh grants and the supervisor's one-shot,
fixed-destination monotonic send relay, preserving TLS/name/leaf pins and
bounded JSON/SSE response handling. Keep the relay alive while a streamed
response is consumed. Pure low-level protocol tests do not prove production
effect authorization; test the production path and actual denied/expired wire
effects separately.

Reuse the same typed grant key/domain, current-authority transaction, durable
intent, owner CAS, no retry/redirect and original execution deadline. Do not
infer target commit/deduplication or successful finalization from a broker MCP
reply. Missing review/authority, duplicate or changed effects, stale profile,
lost gate reply and expired send windows fail closed.

## Delivery

Canonical contracts precede generated bindings. Use small changes and focused
tests/fixes, then complete the native MCP increment and applicable stage
regressions before its commit. The preceding HTTP checkpoint was separately
committed only at the user's explicit request. S18 remains Partial, earns no
weight, and still requires legacy unbound-write migration and full stage gates.


The remaining S18 legacy migration refuses unbound OLO write issuance and
consumption, including still-valid historical grants. Return the typed
BOUND_SERVICE_EFFECT_REQUIRED conflict so operators can upgrade the executor
and target together. Legacy reads remain compatible. Bound writes retain their
existing target-side claim and prepared-request protocol.


Owned-stack image qualification also accepts an exact runtime-source content
identity from knowledge_image.py, so this stage can be tested before its single
commit. It includes tracked and untracked runtime inputs, refuses source links,
checks the source again after building both images and verifies the inspected
owner/revision/content labels. Legacy clean-revision evidence remains supported.
A stale or malformed content identity is refused; an image-preparation report
does not establish runtime or stack acceptance.
