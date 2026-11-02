<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 051: Verified leaf SPKI pins

**Status:** Accepted and delivered; standard TLS plus verified-leaf pin tests and shared MCP compatibility pass the source-bound S13 matrix.

## Defect and decision

The original custom SPKI verifier accepted any matching public certificate in a
peer-provided chain, without validating that chain, its hostname or lifetime.
A copied pinned intermediate could therefore be appended to an unrelated leaf.
Even checking a normal chain before searching arbitrary supplied intermediates
would not establish that the matched certificate belongs to the verified path.

Require ordinary server certificate verification (system trust, or exactly the
operator's explicit CA bundle) and then match an allowed SPKI pin against the
verified leaf only. Delegate certificate and TLS handshake-signature validation
to the standard Rustls verifier. Keep HTTP/1.1, no redirects/retries, checked DNS,
and the existing refusal of SPKI mode with service mTLS.

This intentionally narrows previous pin-only behavior. Operators using a private
or self-signed service must provide its public trust anchor in caBundle; use leaf
SPKI values in spkiPins. An intermediate pin alone is no longer sufficient.
There is no fallback to pin-only trust after a validation failure. Upgrade the
profile's immutable pin/CA configuration before adopting this host version.
This security correction is within S13 and does not change stage weights.

## Required evidence

Use actual TLS peers and application-contact counters: trusted valid pinned leaf
succeeds, while unknown issuer, wrong leaf pin, hostname mismatch, expired leaf
and an unrelated copied pinned intermediate fail before application bytes.
The complete S13 matrix and source/image gate remain outstanding.
