# Security Invariants

Managed device execution requires a registered device, current owner/device enablement, installed-client connection approval and the existing agent/tool/action/resource filters at its protected boundaries. Registration, green availability or deployment never grants execution. Unlimited connection approval never grants permanent certificates or wider tool/resource permissions.

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


## Zero Trust Execution invariants

Adopt the [C1–C8 requirements and A1–A9 attacker matrix](zero-trust-boundaries.md).
Their [live implementation inventory](zero-trust-execution-status.md#security-claim-inventory)
controls availability; these invariants are requirements, not a claim that the
entire roadmap is implemented.

- Control alone authorizes. Gateway, reviewers, worker input and returned content
  cannot mint authority. Admission permission does not authorize arbitrary later effects.
- Bind admission intent and, on qualified effect paths, the complete request,
  generation and owner. Refused or unavailable authority cannot open an effect path.
- Worker isolation requires a qualified runtime/profile; native execution and
  route declarations alone do not establish containment.
- Distinguish healthy enforcement from root compromise. Revocation refuses new
  Control authority; it does not revoke credentials already held at unfenced targets.
- Durable intent precedes its governed transition. Consumption or dispatch is not
  business success; missing evidence is not proof of no effect.
- Never redispatch ambiguous effects or reopen consumed attempts through legacy
  retry. Target replay is status-only; APPLYING is not proof of not-started.
- Preserve late and contradictory evidence without restoring execution authority.
  Durability is not tamper evidence against a database owner; signed external
  anchoring and offline verification remain planned.
