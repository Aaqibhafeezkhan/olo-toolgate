<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 050: Strict qualified-worker protocol boundary

**Status:** Accepted and delivered for the declared Linux x86_64 Python OCI tier; source-bound S13 matrix evidence is recorded in the authoritative status document.

## Decision

The declared isolated Python OCI tier must reject malformed worker stdout before
forwarding events, contacting authority or accepting a later terminal result.
Use the canonical offline schemas and duplicate-preserving strict JSON parser.
Refuse invalid UTF-8, duplicate keys at any depth, unknown fields/kinds, invalid
values, truncated frames, excessive depth/bytes/frame counts and reused broker
request IDs. Require one terminal result and refuse any later nonblank frame.

A result printed while a broker call is outstanding cannot complete the run:
the owned worker channel remains serialized until that call settles or the
invocation is cancelled. No protocol failure settles an ambiguous target effect;
the existing Control effect/outcome journal remains authoritative.

This is an explicit tightening for the qualified tier. Legacy HOST_NATIVE is
separately declared and receives no OCI qualification from these changes.
Retain bounded stderr, aggregate output, deadlines, leases and paired teardown.
Errors contain fixed diagnostics without worker payloads or secret values.

## Evidence required

Focused parser/state tests, actual child-process malformed/flooded/fragmented
streams and outstanding-call result ordering, actual OCI interface attacks,
egress refusal/approved contact and no weaker-tier admission. Bind the complete
declared-tier matrix to current source/image evidence; disclose unsupported
transports and tiers instead of claiming every historical catalog is accepted.
S13 earns no planning weight until its complete stage verification passes.
