<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 045: Source-bound actual Control/knowledge qualification

**Status:** Accepted for the owned development-transport fixture.

A successful HTTP tool response does not prove that target outcome delivery
settled at Control. Full-stack qualification must join committed target claims
and durable outcome acknowledgements with the actual Control generation records.
Effect identity, nonce, generation, audience consumer and COMPLETED state must
agree; a missing grant, contradictory state or premature delivery acknowledgement
fails the gate. Both memory and RAG require committed cases.

Record the actual running container image ID and its known source revision,
rather than inspecting a mutable tag only at the end. Pin component source and
installed application JAR hashes before/after execution. Reject source directory
links, escaped files, changed artifacts, incomplete cases and stale evidence.
Retain only non-credential claim metadata in sanitized reports.

The runtime image may be built from a known clean parent while qualification
runner code is developed separately; reports name that actual runtime revision
and the runner/component source digests. New runtime changes require rebuilding.
This gate uses fresh owned Docker resources and real Control, Gateway, Tool Host,
TLS targets and databases. Its loopback development transport does not establish
CNI containment, production revocation SLOs, compromised-kernel protection or
complete roadmap acceptance.
