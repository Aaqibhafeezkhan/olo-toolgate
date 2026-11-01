<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Prepared broker HTTP requests

The Tool Host now builds one buffered request, normalizes that owned request,
and computes a versioned JCS manifest before sending the same request value.
The private transport cannot be edited after preparation. Redirects and automatic
reqwest protocol retries are disabled on production egress clients.

The manifest binds the Control-signed service profile digest and profile ID,
route ID, scheme/lowercase host/port, uppercase method, encoded URL path, query,
normalized content type, digest of exact body bytes, and remaining outgoing
headers. Query names are sorted in their encoded wire form; duplicate-name values
keep their order. Content type media/parameter names are normalized and duplicate
parameters are refused. Charset names are lowercased; boundary values and quoted
semicolons are preserved. The normalized query/content type are actually sent.

Credential header values never enter the manifest. Their declared lowercase
names and auth declaration ID remain bound. Unrecognized credential headers and
credentials overlapping transport/body semantics are refused. Header credentials
are supported; structured query/cookie credential injection remains unsupported.
Fixed broker transport headers are excluded. Tools cannot override User-Agent,
traceparent or tracestate, or supply duplicate header names differing only in case.
Streaming request bodies cannot be bound; broker route bodies are already buffered
and bounded. Arbitrary exact body bytes, including explicit compressed bytes, are
hashed without parsing/reformatting; Content-Encoding is bound as a normal header.

A prepared-request digest is recorded in broker audit telemetry. It is an
**executor observation**, not a grant or a target receipt. This increment does not
send the manifest/digest to Control's effect ledger or enforce cross-call effect
binding there. The next task must add the authenticated broker effect protocol,
validate the pinned profile/route at Control and require an authorization before
sending. Consent slots, target verification and shared target claims remain
outstanding. Do not infer target deduplication from the single local send policy.

Shared vectors are in `tests/fixtures/security/http-request-binding-v1.json`.
Tool Host tests construct the real request and compare the manifest/digest;
Control's JCS test independently checks the same digest. Other tests alter body,
recipient and duplicate-query order, verify credential rotation does not enter the
manifest, and force an ambiguous TCP disconnect after the body to confirm one
request was received. HTTP/2 GOAWAY and broader multi-target conformance remain
part of the full phase-3 acceptance suite, not established by that TCP test.

Existing routes that let tools set broker-owned headers must remove those
allowances. Content-type parameter normalization may change cosmetic header
formatting; target semantics such as boundary case are preserved. See
[ADR 037](../adr/037-prepared-http-request-binding.md) and the
[live progress document](../security/zero-trust-execution-status.md).
