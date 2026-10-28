# Security

ToolGate is in Beta Release. See [production and security status](../../README.md#production-and-security-status)
for current limitations and their open enhancement tickets.

Control is the authority for runtime decisions and effect permits. Default
membership, tool installation, device approval and management roles do not grant
runtime access. Protected effects require current authorization; unavailable or
invalid authority fails closed.

## Review the model

- [Security invariants](security-invariants.md)
- [Enterprise access control](enterprise-access-control.md) and [user roles](user-roles.md)
- [Trust boundaries](../architecture/trust-boundaries.md)
- [Threat model](threat-model.md), [Tool Host threat model](threat-models/tool-host.md) and
  [OLO integration threat model](threat-models/olo-integration.md) (proposed)
- [Device enforcement](endpoint-enforcement.md) and [key management](key-management.md)
- [Enterprise evidence and limitations](../enterprise-access-control/README.md)

## Audit and secrets

Record administrative mutations, approval decisions, deployments, revocation,
permit consumption and runtime correlation. Administrative retries are idempotent.
Redact passwords, bearer tokens, cookies, private keys, sensitive arguments and
credential values. Public device details and configuration exports must exclude
private custody. Preserve audit, nonce and retirement history during recovery.

Quickstart has local encrypted custody. Production requires separately provisioned
identity and keys; a planned vault adapter is not an available integration.
See [production setup](../deployment/production.md),
[release supply-chain checks](../development/release-process.md) and
[incident/recovery guides](../operations/README.md).

Report vulnerabilities privately through [SECURITY.md](../../SECURITY.md).
