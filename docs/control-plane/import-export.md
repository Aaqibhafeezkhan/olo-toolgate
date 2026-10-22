# Configuration Import/Export

## Purpose

One configuration file (`ControlSnapshot`, formatVersion 2) carries every non-secret
definition and the server settings. The same format initializes a fresh install
(`TOOLGATE_INITIALIZE_FILE`, see [Quickstart](../deployment/quickstart.md)), backs up an
instance and restores it, onto the same or another instance. Plain secrets, tokens,
passwords and vault values are never in it.

Use [API semantics](../api/control-plane-api.md) and the
[valid dry-run example](../examples/control-import.json). Exports have an optimistic
tenant revision; imports require it in both snapshot and If-Match. Unknown versions
are rejected.

## What the file holds

| Field | Exported | Imported |
|---|---|---|
| teams, roles, agent/tool/device groups (defaults included), grants, policies, bindings | yes | yes |
| delegations, agent delegations, extractors, identity-provider definitions, tools, agents, devices | yes | yes |
| workload bindings (token hash and epochs, never the token) | yes | yes |
| `settings` (`gateway`, `deviceApproval`, `configuration`) | Super Admin exports only | Super Admin imports only |
| `requiredIdentities` (user IDs and their team IDs) | always | read to resolve identities |
| users | never (`users` is empty) | optional |
| team `userIds` | never (empty) | added to, never replacing existing members |
| identity bindings, device evidence | never | preserved on the destination |
| `visitorPool`, `reservedRecords` | never | read only by Quickstart at install |

User records may carry `email`, `showOnLogin` (listed with its generated password on
the Quickstart login page) and `claims`, a map of non-secret attributes such as
`{"employee_id": "E-101"}`. No password, hash or other secret exists on a user.

A workload binding may carry `passClaims`: claim name to the tool ID or MCP name globs
(`*` only as the last character) the delegated user's claim is passed to, for example
`{"employee_id": ["hr.*"]}`. Control copies only those claims, from the delegated user's
record, into `identity.claims` of the invocation permit; nothing else reaches it.

## Export

`GET /api/control/v1/config/export` needs `export` on every group. Users are left out,
and so are team memberships. `requiredIdentities` lists, by ID only, every user an
exported record references (team members, agent and device owners, the auto-approval
owner, delegated workload users) with the teams each belonged to.

## Import: Merge and Replace

`POST /api/control/v1/config/import` with `{"snapshot", "mode", "dryRun",
"identityResolution", "removeUnlistedMemberships"}`.

- **Merge** keeps existing records and updates duplicates by ID.
- **Replace** also deletes records of exported kinds that the file does not list. It never
  deletes users, team memberships, identity bindings, device evidence or the default
  groups. When a preserved record would point at a deleted one (device evidence for a
  removed device, for example), Replace is refused:
  `409 {"error":"REPLACE_CONFLICTS","ids":["DEVICE_EVIDENCE:evidence"]}`.

### Required identities

Every `requiredIdentities` ID must already exist on the destination (or in the file's
users), or be resolved in `identityResolution`, before anything is applied:

```json
"identityResolution": {
  "alice": {"action": "MAP", "targetUserId": "carol", "teamIds": ["Support"]},
  "bob":   {"action": "PLACEHOLDER"},
  "admin": {"action": "EXISTING", "teamIds": []}
}
```

| Action | Effect |
|---|---|
| `EXISTING` | The user must exist; nothing is rewritten |
| `MAP` | Every reference to the ID is rewritten to `targetUserId`, which must exist |
| `PLACEHOLDER` | A disabled user with that ID and no credentials is created, to be enabled or remapped later |

Unresolved IDs refuse the import, dry runs included:
`409 {"error":"UNRESOLVED_IDENTITIES","ids":["alice"]}`. The resolved user is **added**
to its ticked `teamIds`; teams it already belongs to on the destination are kept unless
`removeUnlistedMemberships` is `true`, which removes resolved users from the other teams
(never from the default team). Delegated workload bindings whose user was mapped or
created as a placeholder arrive `enabled: false, needsReauthorization: true`; an
administrator reauthorizes them by rotating their credential
(`POST /api/control/v1/workload-bindings/{id}/credential`), after which the previous token
fails.

### Settings in an import

A snapshot with `settings` needs a Super Admin (`403` otherwise). Groups that differ from
the saved settings are applied with the directory change and audited
`SERVER_SETTINGS_IMPORT`. An import cannot change `configuration.requiredReviews` while
reviews are required: `409 {"error":"REQUIRED_REVIEWS_CHANGE"}`; change it in Server
settings instead.

## Server settings

`ControlServerSettings` (formatVersion 2, `packages/contracts/schemas/v2/settings.schema.json`)
is read and written with `GET`, `PATCH` (merge patch per group) and `PUT` on
`/api/control/v1/settings`. **Every settings call is Super Admin only.** Writes need an
`Idempotency-Key` and the current revision in `If-Match`; a stale revision returns `409`.

```json
{
  "formatVersion": 2,
  "revision": 4,
  "gateway": {"gatewayName": "ToolGate"},
  "deviceApproval": {"autoApprove": true, "durationMs": 2592000000, "ownerUserId": "admin"},
  "configuration": {"requiredReviews": 2},
  "clientCheckIn": {"checkInMs": 2000, "jitterPct": 20, "offlineAfterMs": 120000, "source": "DEFAULT"}
}
```

| Setting | Default | Meaning |
|---|---|---|
| `gateway.gatewayName` | `TOOLGATE_GATEWAY_NAME`, else `ToolGate` | Name devices show beside this gateway's URL |
| `deviceApproval.autoApprove` | `false` | New enrollments are approved at once |
| `deviceApproval.durationMs` | 30 days | Approval length for auto-approved devices, 1 to 3650 days |
| `deviceApproval.ownerUserId` | none | Enabled user that owns auto-approved devices; required while auto-approval is on |
| `configuration.requiredReviews` | `TOOLGATE_CONFIG_REQUIRED_REVIEWS`, else `2` | Reviews a configuration change needs: `0`, `1` or `2` |
| `clientCheckIn` | from the deployment | Read-only; always the running Control's values |

### Configuration reviews

A configuration change keeps the `requiredReviews` in force when it was created; later
threshold changes do not alter pending changes. With `0`, changes apply immediately and
are audited `CONFIGURATION_DIRECT_NO_REVIEW`; the Super Admin direct path is unchanged
(`CONFIGURATION_DIRECT_SUPER_ADMIN`). Changing the threshold itself needs the current
threshold's reviews with no Super Admin bypass: while it is above `0`, a settings write
that changes it returns `202` with a configuration change of kind `SETTINGS`. Its
reviewers need `approve-configuration` on every group, the requester cannot review it,
and a Super Admin review counts once like any other. Every signed-in console user can
read the threshold at `GET /api/control/v1/review-policy` (`{"requiredReviews": 0}`).

> **Security:** with auto-approval on, any device that can reach the enrollment
> endpoint receives an identity for the configured duration. Every auto-approval writes
> an `ENROLLMENT_AUTO_APPROVE` audit entry next to `ENROLLMENT_CREATE`.

### Environment at bring-up

The separate `server-settings.json` startup import is removed; settings arrive in the
configuration file. These variables remain:

| Environment variable | Meaning |
|---|---|
| `TOOLGATE_SETTINGS_AUTO_APPROVE_DEVICES` | `true` or `false` |
| `TOOLGATE_SETTINGS_AUTO_APPROVE_DURATION_DAYS` | Days, such as `30` or `30d` |
| `TOOLGATE_SETTINGS_AUTO_APPROVE_OWNER` | Owner user ID for auto-approved devices |
| `TOOLGATE_GATEWAY_NAME` | Gateway name used until settings are saved |
| `TOOLGATE_CONFIG_REQUIRED_REVIEWS` | `0`, `1` or `2` (default `2`), used until settings are saved |

The provisioning coordinator applies these inputs once, together with the tenant's initial configuration, receipt and activation. Subsequent starts preserve saved settings. The seed writes a tenant-scoped `SERVER_SETTINGS_IMPORT` audit entry.

`TOOLGATE_DEFAULT_TENANT_ALIAS` selects the default provisioning record; runtime identity is its persisted UUID. `TOOLGATE_CONTROL_ENDPOINT_TENANT_ID`, `TOOLGATE_DEFAULT_TENANT_ID` and `TOOLGATE_CONFIG_IMPORT_OVERWRITE` are unsupported and rejected, including an overwrite value of `false`. Later settings changes use the reviewed runtime workflow.

### Protected initialization manifest

`TOOLGATE_INITIALIZE_MANIFEST` selects a local JSON/YAML manifest described by
`config/initial/tenant-manifest.schema.json`. The default alias must name one of its
tenants and agree with the environment selector. Aliases and normalized hostnames
must be unique. For example:

```yaml
formatVersion: 1
defaultTenantAlias: default
commonImports:
  - common.json
tenants:
  - alias: default
    name: First organization
    hosts: [tenant1.example.com]
    administratorProvisioningRef: reviews/first
    imports: [first.json]
    endpoints:
      controlUrl: https://tenant1.example.com:8443
      gatewayUrl: https://tenant1.example.com
```

Each protected review directory contains `packet.json` and `trust.json`. The signed
packet establishes the tenant UUID and its reviewed administrator. Tenant imports
must carry that exact UUID. Common imports omit tenant identity and cannot contain
users, devices, identities, workload credentials or populated memberships. Their
configuration is copied once; subsequent template edits do not change tenants.

Inputs are confined to the manifest directory, including resolved symlinks. Each
manifest/import/review packet is at most 2 MiB, trust files at most 16 KiB, and all
files read together at most 16 MiB. Repeated references count toward the total.
Missing explicit files, path escapes, duplicate records and invalid effective
configuration are rejected before any tenant is allocated.

Optional `endpoints` records the tenant's canonical HTTPS Control and Gateway
origins. Both hostname owners must be the tenant declared in the manifest. Its
optional `serverId` defaults to that tenant's UUID; the organization name comes
from `name`. The profile is tenant-owned under PostgreSQL RLS and is inserted by
platform provisioning in the activation transaction. Runtime cannot insert or
change it. Default-only bring-up captures the explicit deployment endpoint
settings into the same profile. Profile/host changes do not reopen initialization.

Records replace by kind and ID; omitted records remain. Settings objects merge
recursively, while arrays, scalars and explicit null replace. Final schema,
reference, membership and protected-administrator checks apply to the result.
Quickstart follows these same replacement rules before expanding its explicit
`requiredIdentities` and visitor-pool setup instructions.

Activation commits configuration, settings, hosts, audit and the initialization
receipt together. Concurrent replicas cannot initialize twice. An apply failure
rolls back that state and leaves the tenant `PROVISIONING`; the platform registry
stores only `INITIALIZATION_FAILED` and its timestamp, without input contents or
exception text. A successful retry clears the diagnostic. An initialized tenant
is never reseeded, even when sources or environment values change.

The manifest implements provisioning. Shared-host request routing and client
binding remain under development as tracked in `plan.md`.
