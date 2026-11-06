<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Target-deduplicated recovery

This feature uses fresh breaking builds. Deploy matching current Control,
Gateway, Tool Host, client, console, contracts and SDK artifacts onto fresh
databases. There is no database upgrade, backfill, default write-key alias or
backward artifact path. Current execution crash recovery and late evidence remain.

## Before enabling recovery

1. Give every logical write a stable explicit effect key. Java RouteCall.effectKey
   and Python effect_key carry private broker metadata. A different key names a
   different effect and consumes a separate exact-consent use. Missing write keys
   are refused; default and service-write are ordinary distinct literal keys.
2. Declare DOWNSTREAM_IDEMPOTENCY on the tool and review every write route's
   exact effect intent. The broker owns the derived target header and value.
   Tools cannot choose or overwrite it, and it cannot replace credential,
   conditional-request, content-encoding or hop-by-hop headers.
3. Put documented target facts in each write policy's targetDedupeGuarantee:
   documentationUrl, retentionMs, GLOBAL/ACCOUNT/API_KEY scope,
   scopeBindingDigest, REJECT/REPLAY_FIRST differentBody behavior,
   reviewExpiresAtUnixMs and header. Scoped credentials also require the exact
   current credentialVersion. There are no default retention or scope facts.
4. Apply a matching ToolDefinition.targetDedupeGuarantees configuration for the
   exact profile digest and route through independent configuration review.
   At least one current scoped checker must be independent from the requester
   and route-profile maker. Super Admin and zero-review settings cannot bypass
   this requirement. Direct edits, imports and a changed tool/profile cannot
   reuse an earlier approval.
5. Configure independently qualified executor pins for the actual Python OCI
   device/pool/image. Every consumed attempt needs a matching admission-time
   scope; runtime self-reports cannot create it. Generic routes must be explicitly
   reviewed, non-streaming HTTP behind the broker. API-key auth-only vault
   references may remain outside worker variables. Raw worker secret declarations,
   other auth modes, TCP and unqualified runtimes cannot qualify this recovery.

The effective window is the documented retention minus the greater of ten percent
and one hour, anchored to the first authorization. Current review expiry and the
original operation deadline further shorten it. A replay never renews this window.
Each authorized send still has a fresh owner-bound window of at most two seconds.

## Read-only status adapter

A guarantee may name statusRouteId in the same immutable profile. Declare the
route in the package/tool so deployment retains it. It must have an explicit READ
effect review, use GET on the write's same approved HTTPS destination and auth,
have no query, caller headers or streaming, and cap its response at 64 KiB.
The supported path has exactly one constrained slot, for example:

    /effects/{effectKey:tg-[a-f0-9]+}

Literal path segments use ASCII letters, digits, slash, underscore or hyphen.
Control substitutes its stored key. It never accepts a lookup URL or target key
from the operator. Current static API-key and unauthenticated routes are supported;
other credential adapters remain manual.

The provider implements the closed TargetEffectStatusReply contract. A completed
response includes key, state COMPLETED, the exact received request bodyDigest,
a stable businessReceipt and resultDigest. A FAILED witness must durably prove
not-applied and fence late submissions for that key. A normal error, 404,
missing key, unknown or pending request never supplies that proof. Redirects,
ambiguous HTTP framing, oversized data, invalid UTF-8, mismatched keys/bodies,
untrusted hosts/CAs or wrong verified-leaf pins are refused.

A human with current scoped operation read and approval permission invokes:

    POST /api/control/v1/access/invocations/{operationId}/effects/{effectId}/lookup

Control requires an unknown operation with no active generation in that effect
family. It checks the current profile, independent review, credential version,
epoch and all generations before I/O and again in a writer transaction afterward.
The network call holds no database writer. A competing recovery or revoked
authority prevents a late response from becoming evidence. The response exposes
effect status and an outcome revision, never the key, raw provider receipt or
credential.

Matching terminal evidence is appended under TARGET_LOOKUP with a scoped
business-receipt hash. Original AMBIGUOUS generation rows remain history.
Unknown/pending responses preserve uncertainty. Conflicting receipts force
RECONCILIATION_REQUIRED and cannot reopen retry. A known target effect does not
prove the worker finished its entire operation: the derived outcome may remain
unknown or partial until complete attempt evidence is available. A result that
relied on an actual target-deduplicated replacement retains that assumption in
its evidence grade.

## Release preflight

Operator Insights exposes Recovery preflight even when telemetry is off. Run it
for all tenants or one tenant UUID. It lists every affected downstream-idempotency
tool, current route reviews, missing/expired guarantees, lookup capability,
qualified executor configuration and the effective fallback method. Pagination
is bounded and a changed tenant directory fences its cursor. Refresh after
configuration changes. This view never enables recovery or authorizes a resend.

The operator mTLS API is GET /insights/v1/recovery-eligibility with optional tenant,
cursor and limit (1–100). The tenant API is GET /api/control/v1/recovery-eligibility;
it filters each row by the human's current tool-read scope. Configured eligibility
still requires per-operation complete qualified history, exact immutable effects,
current policy/consent and a live original window.

Automatic resend records RECONCILIATION_RESEND and creates a new attempt/generation
for the same operation/effect set and downstream keys. It cannot add effects or
hide predecessor evidence. Outside those conditions use supported read-only
lookup or independent manual reconciliation. ToolGate does not claim exactly-once
effects on external systems.
