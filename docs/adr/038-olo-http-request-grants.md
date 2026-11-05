<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 038: Bind OLO service permits to prepared HTTP request digests

**Status:** Accepted for the existing OLO service authorization path.

## Decision

Add an optional version-1 HTTP request digest to service permit issuance, signed
claims and consumption. The broker prepares its immutable buffered request before
issuance, then injects only the excluded service-permit credential. Control signs
the digest and includes it in the immutable effect binding. A changed digest
cannot reuse the same write capability. The target verifier recomputes the
fingerprint from trusted adapter-supplied actual HTTP facts and checks it before
online consumption. A bound permit without transport facts fails closed.

This does not authorize new routes or effects. Existing identity, operation,
resource and exact-body checks remain mandatory. Control accepts this digest as
an authenticated broker assertion; it does not independently validate the full
profile/route manifest or approve an exact consent slot. Generalized effect-grant
APIs, target shared claims and consent remain separate work.

The new fields are optional for legacy consumers, with absent values preserving
legacy permit behavior. New brokers always request bound service permits and
require upgraded targets; old target integrations refuse them rather than
silently skipping HTTP checks. Upgrade Control and the target verifier/integration
before the broker. Generated Java constructor signatures change with the added
optional field; source consumers must regenerate/recompile together.

## Target integration

Transport facts must come from the accepted request and trusted service routing
configuration, never a submitted manifest or digest header. Preserve raw encoded
path/query and all repeated header values in their wire order. The helper hashes
the exact buffered body and excludes only declared credentials and broker
transport headers. The broker has already normalized the sent query and content
type: targets must preserve those wire values rather than normalize alternatives.
