<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 040: Bind actual knowledge listeners to trusted HTTP profiles

**Status:** Accepted for coordinated ToolGate / olo-mono integration.

The memory/RAG applications live in olo-mono/olo-knowledge. Their HTTPS listener
passes accepted URI/method/repeated headers and body to the target verifier,
using an operator-provisioned profile/route map and public origin. Request
headers and unverified claims never select trusted profile metadata.
Configuration is bounded, closed and loaded before the listener starts. The
deployment fixture records the approved Control profile before launch.

A verifier request containing transport facts now requires a bound HTTP claim.
It cannot silently accept a legacy permit and skip those facts. Missing facts
for a bound permit and missing binding for a transport-aware request both fail
before consumption. The legacy four-argument request retains its explicitly
unbound behavior for other integrations.

The knowledge apps and package plugin upgrade together to the development SDK.
Component qualification uses real PostgreSQL/TLS/Qdrant/Ollama with a clearly
scripted Control; it is not full-stack or production SLO evidence.
A revision/source/SDK/image-bound report retains actual executed counts and
rejects changed source and stale passing evidence. The authoritative issue-230
status remains the ToolGate progress document.
