# Security Invariants

Managed tool execution uses a trusted registered device, current owner/device enablement, installed-client connection approval, and the existing agent/tool/action/resource filters before every protected effect. Registration, green availability or deployment never grants execution. Unlimited connection approval never grants permanent certificates or wider tool/resource permissions.

See [Device registry and tool-call controls](../control-plane/device-registry.md).

## Purpose

Default deny; policy error never ALLOW; ASK never bypasses approval; secrets never appear in ordinary exports/logs; invalid signatures fail; client never executes unassigned package.

Super Admin management authority is resolved from current enabled directory
membership, never a token role or cached actor flag. It permits all tenant
management actions, immediate audited directory changes, and self approval of
configuration, operation and reconciliation requests. Scoped roles retain
independent review and management ceilings. The exception does not supply
runtime grants, skip policy obligations, authorize stale/expired requests,
restore retired credentials, remove required default groups, or disable the
last active Super Admin. See [ADR 022](../adr/022-super-administrator-review-exception.md).
