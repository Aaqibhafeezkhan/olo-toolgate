<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 039: Independently validate prepared HTTP manifests at Control

**Status:** Accepted for OLO service grants.

## Decision

Bound OLO permit issuance requires the credential-free version-1 prepared request
manifest and the original signed invocation permit. Control verifies its own
purpose-bound signature, current consumed execution fence, admitted identity,
tool/package/argument/resource binding and original execution deadline. An expired
admission window is not an expired running execution: current lease/authority and
the original execution deadline remain mandatory.

Control loads the immutable service profile by the signed digest, verifies its
canonical digest and revision, and checks granted route, brokered HTTPS
destination, method, encoded path, query allowlist, content type, semantic headers
and admitted exact body digest. It recomputes the full manifest fingerprint before
issuing the service permit. Credential bytes and raw bodies are never submitted.

Digest-only bound issuance is refused. Legacy entirely unbound issuance remains
available only without a v2 execution lease; leased v2 executions cannot omit the
manifest to bypass validation, and cannot downgrade an existing bound
write nonce. Upgrade Control and broker together; target verification from ADR 038
is unchanged. Schemas and generated Java constructor signatures grow together.

Path variables are accepted only with unambiguous boundaries and safe linear
character-class constraints. Unsupported template patterns fail closed.
Body-size enforcement remains at the broker/target: a digest cannot prove length.
This is not exact consent, generalized grants or target deduplication. Those
remain separate requirements and must not be inferred from route validation.

The original issuer verifies the submitted arguments when minting the signed
admission. Its consumed nonce binds that admission to the invocation. The signed
v2 JCS argument digest and the legacy service exact-body JSON fingerprint are
different digest domains (for example numeric `1.0` versus `1`); they must not
be compared as interchangeable hashes. Control independently checks the manifest
body digest against the stored exact-body fingerprint.
