# Enterprise access control

Control evaluates current tenant identity, group grants, execution bindings,
policy and request facts. Authentication, administrator access, ownership,
installation and group membership do not supply missing runtime permission.

## Entities and groups

| Entity | Group | Runtime meaning |
|---|---|---|
| User | Team | Human grants apply through enabled membership |
| Agent | Agent Group | Capability ceilings, service authority and delegated scope apply through the group |
| Tool | Tool Group | Each tool has its primary group and required execution binding |
| Device | Device Group | Bounds the registered executors on which the tool may run |

Protected default groups prevent orphaned entities; they grant no execution.
Tenant references and memberships must stay within their tenant. Creating or
enabling an identity does not authorize a call.

## Permission model

A grant retains a complete tuple: tool-group scope, device-group scope, action,
resource and conditions, together with its human/actor/service purpose. Do not
flatten unrelated memberships into an accidental allow. Agent ownership is not
delegation, and a management role is not an execution capability.

Tool Group to Device Group bindings constrain where a tool may execute. The
actual caller, agent, tool and executor remain verified request facts; an event
body, browser selection or caller-supplied device ID cannot choose another identity.

Policies narrow a valid grant. ALLOW does not create missing access, ASK adds
approval obligations, and BLOCK wins. Tool discovery is not proof that a later
execution remains authorized.

See [canonical contracts](../../packages/contracts/README.md) for exact fields
and [initial configuration](../../config/initial/README.md) for protected defaults.

## Device and invocation checks

An installed client needs its enrolled key, current owner/device enablement and
connection approval. Enablement and approval have independent revisions and
lifecycles. Approval expiry or suspension preserves identity but stops protected
work; permanent revocation does not become a retryable enrollment shortcut.

Gateway authenticates the caller and obtains Control's current decision. The
registered executor verifies and consumes Control-issued authority. Signing
keys, permitted effects, argument/target binding, deadlines and replay rules
belong to that execution contract. An unavailable authority fails closed.

The owner-bound client relay is not permission for arbitrary cross-owner/shared
device execution. Use [device controls](../control-plane/device-registry.md) and
[execution security scope](zero-trust-boundaries.md) for the applicable boundaries.

## Administration and review

Human and agent management uses explicit feature permissions and current
backend enforcement. The console shows the data and impact available to the
authenticated user; it is not the authority for a security decision.

Configuration mutations use revision guards and the applicable reviewed change
workflow. Independent review applies where required. The documented Super Admin
self-review exception is audited and does not supply runtime grants or bypass
executor consent/permit checks. See [user roles](user-roles.md),
[console permissions](console-permissions.md) and [configuration review](../control-plane/import-export.md).

## Approval, evidence and recovery

An approval is scoped to the actual operation and valid review context. It must
not authorize changed arguments, another tenant/actor/target or an expired run.
Unknown outcomes stay unknown until the required independent evidence exists.
Never infer safe replay from a timeout, empty journal or executor success alone.

Use [ASK and effect consent](../control-plane/approvals.md),
[operation outcomes](../control-plane/operation-finalization.md) and
[proof-bound recovery](../control-plane/operation-recovery.md).
Outcome grades distinguish observations, target confirmation and fencing.

## Operate and verify

Start with a narrow low-risk grant and verify allowed and denied effects. Change
one dimension at a time: enabled identity, membership, device approval, tool
binding, action/resource, policy, expiry and tenant. Cross-tenant requests and
scope composition across unrelated grants must be denied.

Check revocation before subsequent work, stale mutation conflicts, replay and
argument/target substitution, result visibility and secret-free audit. Run the
[security verification gates](../testing/README.md) on both database dialects
where persistence is involved. A local success is not full distributed or
production qualification.

Use matching current artifacts and fresh schemas. Unsupported stores are
rejected intact; there is no migration/backfill or legacy permission fallback.
Further identity, posture and step-up work belongs in the
[remaining execution-security plan](../plans/zero-trust-tool-execution.md).
