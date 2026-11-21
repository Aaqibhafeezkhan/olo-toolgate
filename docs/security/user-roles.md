# Users, Teams and roles

Users receive authority through enabled Teams and their typed roles. Direct
individual ACLs and the old fixed runtime-template model are retired. Role records
do not create credentials; identity must be verified separately.

| Role type | Responsibility |
|---|---|
| Human Role | Bounded human tool-use rights through Team grants and execution bindings. |
| Actor/Service Role | Explicit Agent capability and service/delegated authority through the relevant groups. |
| Management Role | Scoped administrative actions. The live `SUPER_ADMIN` classification additionally carries tenant-wide human runtime authority. |

Every required identity, group, grant and target must remain enabled and current.
Default membership creates no grant, empty selectors never become wildcards, and
overlapping memberships cannot bridge unrelated rights. Role changes are checked
online by Control; an old signed snapshot cannot preserve revoked authority.

## Super Admin

Super Admin authority comes from an enabled Management Role classified as
`SUPER_ADMIN` on an enabled Team. Token role claims or cached flags cannot supply
this authority. It permits all tenant management and human runtime operations,
including running bots/tools and reading tenant run results. Control derives
HUMAN and SECRET entitlements from that live membership; no per-bot human grant
is required for a Super Admin. Disabling/removing the user, Team or role removes
the entitlement on the next check. The same user ID in another tenant receives
no authority there. Other users still need explicit current runtime grants.

Policy obligations, current sessions, revisions, expiry, device/execution
bindings and credential retirement still apply. Services and delegated actors
retain their own capability/service/delegation requirements; a human Super Admin
role never elevates a workload credential. Tenant-run observation is permitted
for a current Super Admin; ordinary callers retain owner-scoped results.

The initial setup administrator is separately protected from deletion/disablement.
That account protection itself grants no authority; role membership still matters.
The last active Super Admin must remain available.

See [the complete access model](enterprise-access-control.md),
[device trust](../control-plane/device-registry.md),
[configuration review](../control-plane/import-export.md) and
[initial presets](../../config/initial/README.md).
