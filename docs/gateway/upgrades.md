# Gateway upgrades and compatibility

Product, chart and contracts advance to 0.2.0-dev for Module 01. Two new closed
types, AuthorizationRequest and RuntimeAuditEvent, add the runtime surface while
frozen v1 definitions/compatibility fixtures stay unchanged. Rust embeds generated
canonical schema data, so validation needs no network or service source reach-through.
Maven coordinates remain stable and local artifact consumption remains tested.

| Component | Compatible surface |
|---|---|
| Gateway 0.2.0-dev | Contracts 0.2.0-dev; v1 authorization decisions |
| Helm 0.2.0-dev | Opt-in gateway, static config and credential Secret |
| MCP | 2026-07-28 ingress skeleton; no executable capabilities or legacy sessions |
| Control/Client/Marketplace | No runtime integration in this module |

No prior stable runtime exists; no N/N-1 window is claimed. ALLOW is neither a
permit nor execution proof. Signed bundle distribution, OIDC, approvals, execution
and credential brokering are unsupported with no permissive fallback.

Provision valid immutable config/credentials, select the tested image digest,
render/lint, then helm upgrade --wait. Check every replica's readiness and expected
decisions. Secret rotation needs rolling restart: old replicas retain their old
snapshots until replaced. For urgent revocation, remove ingress or replace every
old replica before reopening it. There is no live emergency distribution yet;
restoring an older snapshot can restore older permissions.

Rollback with `helm rollback <release> <revision> --wait` and repeat readiness/
authorization probes. No DB migration exists. Keep the old immutable image and
matching valid credential Secret; expired old inputs reject startup. Kind smoke
tests emergency-deny upgrade and restoration of the prior snapshot.

The common `ci.yml` workflow scans/tests before versioned GHCR publication,
requires the matching version tag and emits SBOM/provenance evidence. The same
publisher retains Maven/raw contracts/Helm/GitHub assets, gateway compatibility
metadata and upgrade notes. Administrators configure the `development-release`
environment protections and registry permissions. Local verification publishes nothing.
