<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 046: Control-owned business-key policy and retention

**Status:** Accepted for implementation in delivery stage S08 of issue #230.

## Decision

Business-key policy belongs to the reviewed Control tool definition and the
deployed package descriptor, never to invocation arguments or caller-selected
namespace fields. Introduce optional admission business-key policy metadata on
the canonical tool definition; absent metadata preserves ADR 034's conservative
explicit-key behavior. Package deployment derives its key requirements and
namespace/argument mapping from the stored descriptor. Resolved optional AGENT
defaults (including legacy descriptors omitting the namespace) preserve the
previous authenticated-agent-or-human explicit-key behavior; opting into
required keys, argument keys or another namespace supplies an enforced policy.
A client release copies that policy into its signed installed authorization
profile; current profile/directory binding remains required. Runtime admission verifies
the current definition/package binding and cannot weaken a required key.

Control enriches the authenticated catalog with its effective key policy, not
device-supplied policy hints. Gateway independently derives explicit/argument-key
identities from that policy. Only a declared unkeyed dedupe window may use an
otherwise unpredictable Control-owned canonical operation ID; Gateway still
checks every actor/input/code/binding field and requires the response context ID
to equal the canonical ID. Native and MCP dispatch/result/cancellation references
use that validated ID, not a predicted RPC ID. Direct human admission also carries
the canonical ID into local dispatch. The console forwards a declared argument
key as its header key rather than generating a conflicting random key.

Control derives namespace identity from verified context. AGENT needs an agent;
DELEGATED_USER needs a human and refuses SERVICE mode; TENANT uses the empty
namespace identity. TENANT keys remain owner-confidential: cross-owner replay
returns conflict without results. Result sharing is not implicitly enabled.
Argument-derived keys come from the declared exact top-level input field, use the
same tenant-separated key hash domain, and must agree with an explicit key if
both are present. Missing, malformed or conflicting keys fail before reservation.

Persist the key source and dedupe expiry. Archive the complete old window binding
in an immutable tenant-scoped history table in the same transaction as rotation.
Same-generation key identity, expiry and retention are immutable. A tombstoned
explicit key can never be revived. A new window generation may supersede its
retained predecessor only with the exact history snapshot and Control's durable,
non-legacy resolved operation evidence. The current journal can prove FAILED
for unconsumed work; consumed terminal work remains unknown until full
effect-aware finalization is implemented, and cannot rotate merely on a report. Optional unkeyed dedupe is a reviewed
policy choice with a bounded window; it never substitutes for a required key.
Use the existing strict full invocation fingerprint rather than weakening to a
package-major-only identity. A dedupe window cannot renew or replace an unresolved
or consumed-unknown operation. Replacing an expired dedupe reservation requires a
known resolved prior invocation, a fresh immutable operation identity and the
tenant-serialized transaction; historical invocation/attempt/effect records stay
intact. Explicit keys are never renewed by this path. Whole-invocation proof-based
rebinding remains S09.

Retention is snapshotted at reservation and cannot be shortened on replay.
Default retention is thirty days and must cover the declared downstream
idempotency window. Bounded maintenance marks expired terminal reservations with
tombstones, without deleting their identities or reviving an operation. Pending
or ambiguous work is not purged into not-started evidence. Tombstones remain
indefinitely in this implementation, satisfying the minimum one-year history
without giving the runtime DELETE authority.

## Compatibility and validation

An optional contract field retains legacy JSON behavior and Java constructors;
all bindings are generated from canonical schemas. Upgrade Control/contracts
before clients publishing the new policy. A changed tool/namespace policy is a
reviewed deployment change, not transparent migration of existing logical keys;
settle old workflows before changing namespace or enabling new argument/window
derivation. Raw header/metadata keys are not stored. Argument-derived keys remain part of
the governed tool input and can be retained in dispatch payloads under existing
payload custody; the reservation and ordinary audit store only hashes, not raw
key values. Key hashes are identifiers, not credentials. Key-row retention is
separate from task/result/dispatch payload retention; tombstoning a key does not
claim deletion of those independent records.

SQLite and PostgreSQL must both exercise missing-key refusal, namespace derivation,
owner confidentiality, argument/header mismatch, changed-input conflict, window
expiry/races, unresolved-window refusal, immutable retention and bounded tombstone
maintenance. Run focused tests after each small change and the complete component,
contract and cross-language gates after the full S08 implementation. This ADR
records the intended decision; stage completion requires executed evidence.
