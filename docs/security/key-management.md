# Keys and credential custody

Keep identity, effect, snapshot, fleet/release and device trust domains separate.
Control owns effect-signing keys. Gateway holds no effect-signing or MAC key and
cannot authorize from an old signed snapshot.

| Domain | Responsibility |
|---|---|
| Identity verification | Validate configured issuers and current identity/session facts. |
| Control effect signing | Bind short-lived permits to exact invocation, target and current authority; consume once online. |
| Control snapshot signing | Sign group metadata with monotonic publication state; snapshots never authorize offline effects. |
| Fleet/release signing | Bind immutable packages and generations to assigned devices. |
| Device identity/CA | Bind the enrolled owner and exact key; renewal/recovery cannot extend approval or resurrect revocation. |

Provision private custody outside source control and chart values. Mount it only
in the owning service and preserve restrictive ownership/permissions. Use
[Control configuration](../control-plane/configuration.md),
[Gateway configuration](../gateway/configuration.md) and
[device registry](../control-plane/device-registry.md) for actual settings.

For rotation, identify every verifier and pending artifact before changing a key.
Follow that domain's current keyring and version rules; preserve needed overlap,
monotonic floors and retirement evidence. A key change must never revive spent
permits or restored credentials. Use [reviewed recovery](../enterprise-access-control/operations.md)
for compromise or restore, and verify revocation before reopening traffic.

Server-tool request-state and emergency operator keys are specified in
[ADR 013](../adr/013-tool-host-egress-broker-secrets.md) and
[ADR 017](../adr/017-kill-switch.md). Check the executor guides for current
runtime support; specifications alone do not establish deployment support.
