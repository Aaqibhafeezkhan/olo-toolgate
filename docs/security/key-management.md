# Key Management

## Purpose

Separate keys for Marketplace signing, organization deployment signing, bundle signing, Gateway permits and device identity. Never reuse trust-domain keys.

Policy bundle keys are implemented in Module 04 as a dedicated external RSA trust
domain. Control rejects reuse of its administrative IdP modulus; Gateway accepts
only the configured policy keyring and protected bundle header type. Operators
must also separate Marketplace/deployment/permit/device and release keys. See
[bundle key configuration and rotation](../control-plane/policy-bundles.md).

Module 05 execution permits use a dedicated external Gateway RSA private key and
protected type `toolgate-execution-permit+jws`. Never reuse policy, IdP, organization
deployment or Marketplace keys. A signed permit also requires atomic online jti
consumption, exact request binding and current policy. See [approvals](../control-plane/approvals.md).

## Server-tool execution keys (design gate D2)

ADR [013](../adr/013-tool-host-egress-broker-secrets.md) and ADR [017](../adr/017-kill-switch.md) define these. The Module 05 Gateway permit key above is superseded: Control signs every execution permit, and the Gateway holds no signing or MAC key.

| Key | Domain | Used for |
|---|---|---|
| Control effect key (`toolgate.control.effect.*`) | Existing | Enterprise permits (`toolgate-effect-permit+jws`) and v2 invocation permits (`toolgate-invocation-permit+jws`), told apart by `typ`. Verifiers hold the current key and at most one previous `kid`. |
| Control fleet key | Existing | Fleet documents, plus kill events and pushes (`toolgate-kill-event+jws`, `toolgate-kill-push+jws`) |
| `requestState` key (`toolgate.control.request-state.key`) | New, symmetric, at least 256 bits | Governed-mode MRTR `requestState` (HS256), minted and verified only by Control |
| Break-glass operator keys (`toolgate.control.breakglass.operator-keys`) | New, three RSA public keys | 2-of-3 approval of break-glass kills (`toolgate-breakglass-approval+jws`). The private keys stay with the operators, offline. |
| Workload CA pin | New trust anchor | Tool Host and Gateway workload certificates, separate from the device enrollment CA |

Control refuses to start if any of these equals another configured key.
