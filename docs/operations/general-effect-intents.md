<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Reviewed route intents and fresh generic effect grants

Generic HTTP routes require reviewed request intents and fresh effect grants.
Use the current [security boundary](../security/zero-trust-boundaries.md) and
[target-fencing contract](generalized-target-fencing.md) for the declared scope;
an executor observation is not independent target finality.

In **Server packages → Deploy a version**, review every generic route's effect
classification and exact request intent. The deployment API accepts the same
reviews in its optional effectPolicies array. Control checks the administrator's
current package.deploy permission and adds the reviewer identity and time;
package authors, workers and deployment request bodies cannot supply review
stamps. Reviews are pinned into the immutable service-profile digest. A change
of the active profile prevents new grants or dispatch under an old profile.

A financial or destructive route requires an explicit acknowledgment that the
target is unfenced. Exact-consent-required policies need the current, independently reviewed
[effect consent](../control-plane/approvals.md). An invocation approval is not exact effect
consent. Generic targets cannot be declared as olo-memory or olo-rag; these
retain the independently verified service-permit and target-consumption path.

For example, a declared POST /send route can be reviewed with:

    {
      "routeId": "send",
      "classification": "FINANCIAL",
      "consentRequired": true,
      "acknowledgedUnfencedRisk": true,
      "intent": {
        "path": "/send",
        "query": {},
        "headers": {},
        "contentType": "application/json",
        "bodyMode": "CANONICAL_JSON",
        "body": {
          "recipient": {"$arg": "/recipient"},
          "amount": {"$arg": "/amount"}
        }
      }
    }

The $arg object must contain exactly that one member and a valid JSON pointer.
The empty pointer references the complete admitted argument object. Resolved
argument data is never reinterpreted as a template. Constants, object keys and
pointers in the stored review must already be NFC; original argument values are
not normalized. Query entries resolve to strings and are canonically percent
encoded. Header names are lowercase; credentials and transport-owned headers
cannot be semantic intent headers. The accept header defaults to */*.

CANONICAL_JSON binds the full canonical JSON body; the broker canonicalizes its
JSON body field without normalizing strings. An exact canonical bodyText or
bodyBase64 may also match, but different bytes do not. NONE requires null
body and empty contentType. Dynamic values that Control cannot reconstruct
from admitted arguments or reviewed constants are refused.

A valid generic request requires original signed admission, a live lease,
current device/identity/session/grants/authorization epoch, the current pinned
deployment, and independent intent reconstruction. Control retains the exact
request manifest and reserves an immutable effect/generation/owner before
issuing a separately typed signed grant. Effect slots and deliberate resend use the current
[per-effect key and recovery contract](target-deduplicated-recovery.md).
Duplicates, ambiguous sends and missing target proof never create retry authority.

Immediately before transport the owner must win a fresh AUTHORIZED → SENDING
transition. A denied, false, malformed, unavailable or lost decision does not
authorize a send. Grants live at most two seconds and do not extend the
invocation, credential or lease deadline. The supervisor starts a conservative
monotonic timer before issuance and guards outbound writes through a private,
one-connection Unix socket relay to one freshly resolved, checked destination.
TLS/SNI/chain/leaf-pin verification, HTTP/1.1-only transport, and refusal of
redirects/retries remain active. A complete timely send may receive its response
after the send window. No general-purpose proxy or socket selector is exposed
to a worker or private broker child.

A generic HTTP success is a broker observation, not a target-owned commit
receipt or target deduplication guarantee. Lost replies and uncertain effects
remain subject to the existing conservative operation/reconciliation rules.

## Current schema and transport

Use matching current profiles, contracts and fresh schemas. Missing review,
admission or supported transport fails closed; old formats are not converted.
The bounded generic transport requires the declared private IPC capability and
verified HTTPS. Development loopback transport is a fixture, not production trust.
Configured CA roots or verified leaf pins remain mandatory at the protected boundary.
