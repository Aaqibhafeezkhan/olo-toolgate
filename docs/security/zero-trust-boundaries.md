# Execution security boundaries

This page describes the current trust model and residual risks. It is not a
certification that every execution tier, target or deployment satisfies every
security requirement. Use the declared runtime profile and actual qualification
evidence for the system you operate.

## Trusted computing base

Control's application, database and signing custody, the identity provider and
workload CA, and the host kernel/runtime are trusted. Arbitrary Control compromise
can create authority; kernel/runtime compromise invalidates host isolation.

Gateway routes requests and cannot mint Control permits. Executors and brokers
enforce current Control decisions. Agents, worker packages, event payloads and
upstream responses are untrusted. Tenant management authority is separate from
runtime access; Super Admin's audited review exception does not create a grant.

## Runtime and target scope

- Qualified Python OCI profiles use the documented [rootless isolation and broker
  boundary](../operations/rootless-tool-host.md). Use the exact declared image,
  host configuration and [interface matrix](qualified-containment-matrix.md).
- Native packages are trusted native code, not contained or attested workers.
  Bounded [device file access](../client/hotfolder.md) does not establish universal
  native containment, physical fencing or exactly-once mutation.
- Broker HTTP request binding, effect generations and target-specific online
  consumption apply only to their declared route/target class. An unfenced
  upstream credential can remain usable until that target expires or revokes it.
- Executor observations, target confirmation and fencing are distinct evidence.
  See [outcome derivation](../control-plane/operation-finalization.md).

## Threats and residual risks

| Adversary | Boundary and residual risk |
|---|---|
| Injected agent or malicious package | Current grants and exact request binding constrain execution; granted destinations can still be misused within scope |
| Compromised Gateway | Cannot mint signatures; end-user identity and delegated context need their own authenticated boundary |
| Compromised supervisor/node | Can falsify local observations or disable supervision; a host lease is not target-side revocation |
| Tenant administrator | Tenant isolation and explicit scopes still apply; authorized misuse and the audited review exception remain risks |
| Control/database/signing operator | Outside the runtime authority guarantee; audit chains/anchors cannot prevent false authorized appends or compromise of signing custody |
| Kernel/runtime attacker | No host isolation guarantee survives arbitrary kernel control |
| Network or upstream attacker | Verified transport, request binding and no implicit retries reduce replay/SSRF risk; partitions, misleading responses and approved-destination exfiltration remain possible |

See [Tool Host threats](threat-models/tool-host.md),
[OLO target integration](threat-models/olo-integration.md),
[key custody](key-management.md) and [platform threats](threat-model.md).

## Qualification requirements

C1-C8 remain requirements for declared security scope: no ambient target
authority; verified sender/run identity; current admission and per-effect
authorization; containment and broker-only I/O; bounded revocation/leases;
conservative recovery; and trustworthy, accurately presented evidence.
No single demo or diagnostic scorecard establishes all of them.

Require actual denied effects for malformed/forged/replayed inputs, tenant and
identity substitution, route/argument changes, stale consent, authority outages,
crashes and uncertain dispatch. Retain immutable evidence and never turn absence
of a receipt into proof that retry is safe. See [security invariants](security-invariants.md)
and [verification](../testing/README.md).

Stronger sender-constrained identity, posture, external audit verification and
remaining end-to-end acceptance belong in the
[unfinished plan](../plans/zero-trust-tool-execution.md). They are not promised by
an approved device, a published package, a cloud trace or an available image tag.
