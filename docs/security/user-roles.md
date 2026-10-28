# Users, Teams and roles

Users receive authority through enabled Teams and their typed roles. Direct
individual ACLs and the old fixed runtime-template model are retired. Role records
do not create credentials; identity must be verified separately.

| Role type | Responsibility |
|---|---|
| Human Role | Bounded human tool-use rights through Team grants and execution bindings. |
| Actor/Service Role | Explicit Agent capability and service/delegated authority through the relevant groups. |
| Management Role | Scoped administrative actions; no implicit runtime permission. |

Every required identity, group, grant and target must remain enabled and current.
Default membership creates no grant, empty selectors never become wildcards, and
overlapping memberships cannot bridge unrelated rights. Role changes are checked
online by Control; an old signed snapshot cannot preserve revoked authority.

## Super Admin

Super Admin authority comes from an enabled Management Role classified as
`SUPER_ADMIN` on an enabled Team. Token role claims or cached flags cannot supply
this authority. It permits tenant management and audited self review under
[ADR 022](../adr/022-super-administrator-review-exception.md), while current runtime
grants, policy obligations, revisions, expiry and credential retirement still apply.

The initial setup administrator is separately protected from deletion/disablement.
That account protection itself grants no authority; role membership still matters.
The last active Super Admin must remain available.

See [the complete access model](enterprise-access-control.md),
[device trust](../control-plane/device-registry.md),
[configuration review](../control-plane/import-export.md) and
[initial presets](../../config/initial/README.md).
