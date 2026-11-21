# Remaining execution-security work

Status: unfinished work for [issue #230](https://github.com/olo-labs/olo-toolgate/issues/230).
This is a remaining-work plan, not a certification of the current runtime.
The current [security boundary](../security/zero-trust-boundaries.md),
[containment scope](../security/qualified-containment-matrix.md) and
[operator guides](../operations/README.md) describe implemented behavior.

## Revocation and dispatch qualification

Complete connectivity-class revocation/kill qualification and authenticated
inbound dispatch. Exercise partitions, stale/paused owners, target outages,
supervisor crashes and complete operation aggregation across attempts/effects.
Acceptance requires actual denied effects and measured bounds for each declared
connectivity class; a reachable heartbeat or a local success case is insufficient.

## Audit verification

Finish customer object-lock/transparency-log anchor sinks and deployment wiring.
Add independent offline `toolgate audit verify` validation for chain edits,
truncation, splicing, signing-key rotation/revocation and anchored history.
Acceptance must distinguish the last verified anchor from an unanchored suffix
and must not claim protection against arbitrary Control/signing-key compromise.

## Identity and posture

Implement sender-constrained agent-run credentials, narrowing delegation and
continuous executor-posture verification. Prove stolen-credential replay refusal,
scope-amplification refusal, delegation expiry and posture rechecks on renewal.
Device registration or an approved connection is not proof of these properties.

## Effect authorization

Complete broker-owned OAuth/token exchange, authenticated provenance policy,
canonical exact-effect previews and stronger step-up approval. Bind approval to
the actual effect, identity and deadline; changed inputs invalidate approval.
Application-signed frontend intents and independently enforced target boundaries
need qualification beyond executor-reported success.

## Release acceptance

Execute the complete security/target/containment matrices on supported platforms.
Make missing, stale or failed evidence block release acceptance. Include
distributed failure/crash scenarios, external security review and the declared
flagship scenario. Retain honest fenced/unfenced/unknown outcome presentation.

Use current schemas and matching artifacts only. No migration, old-store
conversion, compatibility alias or implicit data deletion is part of this work.
Run the [security verification requirements](../testing/README.md) and
[diagnostic scorecard](../testing/zero-trust-scorecard.md); a diagnostic inventory
is not release acceptance.
