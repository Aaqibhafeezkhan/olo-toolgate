# Keys and credential custody

Keep identity, effect, snapshot, fleet/release, device and audit-anchor trust domains separate.
Control owns effect-signing keys. Gateway holds no effect-signing or MAC key and
cannot authorize from an old signed snapshot.

| Domain | Responsibility |
|---|---|
| Identity verification | Validate configured issuers and current identity/session facts. |
| Control effect signing | Bind short-lived permits to exact invocation, target and current authority; consume once online. |
| Control snapshot signing | Sign group metadata with monotonic publication state; snapshots never authorize offline effects. |
| Fleet/release signing | Bind immutable packages and generations to assigned devices. |
| Device identity/CA | Bind the enrolled owner and exact key; renewal/recovery cannot extend approval or resurrect revocation. |
| Audit anchor signing | Sign per-tenant audit-chain heads for external anchoring only ([ADR 061](../adr/061-signed-audit-anchors.md)); verified against a pinned keyring kept outside Control's database. Startup refuses a key that shares any other domain's modulus. |

Provision private custody outside source control and chart values. Mount it only
in the owning service and preserve restrictive ownership/permissions. Use
[Control configuration](../control-plane/configuration.md),
[Gateway configuration](../gateway/configuration.md) and
[device registry](../control-plane/device-registry.md) for actual settings.

For rotation, identify every verifier and pending artifact before changing a key.
Follow that domain's current keyring and version rules; preserve needed overlap,
monotonic floors and retirement evidence. Audit anchor keys rotate by retiring the old
key with a retirement time (earlier anchors stay verifiable) and are revoked on
compromise (their anchors are then refused); keep every keyring version. A key change must never revive spent
permits or restored credentials. Use [reviewed recovery](../enterprise-access-control/operations.md)
for compromise or restore, and verify revocation before reopening traffic.

Server-tool request-state and emergency operator keys are specified in
[ADR 013](../adr/013-tool-host-egress-broker-secrets.md) and
[ADR 017](../adr/017-kill-switch.md). Check the executor guides for current
runtime support; specifications alone do not establish deployment support.


## Execution trust limits

The [adopted Zero Trust Execution model](zero-trust-boundaries.md) distinguishes healthy hosts,
root-compromised hosts and compromised Control/identity/kernel trust roots.
Lease expiry cannot constrain root controlling the supervisor or revoke already
delivered credentials at unfenced targets. Current qualified Python OCI and OLO
fenced-write guarantees do not imply generalized route/consent enforcement,
sender-constrained agent credentials, externally anchored audit or production SLOs.
Signed audit anchors with a local append-only sink exist (S25, unweighted); offline
verification (S26) and customer-bucket/transparency-log sinks remain outstanding.
