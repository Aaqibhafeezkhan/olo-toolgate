<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 037: Prepare one immutable broker request before authorization

**Status:** Accepted for transport preparation; generalized Control enforcement remains a subsequent task.

## Decision

The broker prepares a buffered HTTP request exactly once. It normalizes the
content type and query on the actual owned request, then hashes a canonical
manifest of the pinned service profile, route, destination, method, encoded path,
query, content type, exact body bytes and every non-excluded header. Duplicate
query values retain their order. Credential values are excluded only with their
declared header names and auth declaration ID retained. Transport headers owned
by the broker are excluded; tools cannot supply them.

The prepared request keeps its transport private and sends that same value.
Automatic reqwest protocol retries and redirects are disabled. Binding digests
are local broker observations, not new grants: this change does not send them to
Control or claim generalized per-effect enforcement. The next protocol task must
validate profile/route binding at Control and bind the digest into an effect
authorization before the prepared send. Target request verification and shared
claim SDK integration are also still required.

Only buffered bodies and header-injected credentials are supported by this
preparation API. Query/cookie credential injection and automatic request
compression are not added. Credential bytes never enter the manifest or logs.

## Transport qualification follow-up

The production destination client explicitly selects HTTP/1.1, including when
Cargo feature unification enables additional reqwest protocols. The supported
slice therefore cannot negotiate HTTP/2 or replay writes after HTTP/2 GOAWAY.
This is a protocol restriction, not executed HTTP/2 recovery conformance.
A real CA-verified TLS target offering h2 before http/1.1 verifies the negotiated
protocol and the actual POST/body through the production client.
Future HTTP/2 support requires its own no-replay qualification before enabling it.

Broker egress now also disables idle HTTP/1 connection pooling. Hyper's
canceled-request retry on reused connections sits below reqwest's retry policy;
using fresh HTTP/1 connections removes that path. Each request establishes a
new connection, trading connection reuse for the qualified no-replay behavior.
A real TLS test keeps the first socket alive and requires a second connection
for a second write; restoring pooling makes the test fail.
