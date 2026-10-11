# Configuration Import/Export

## Purpose

All non-secret configuration round-trips through versioned JSON/YAML with validate, diff, dry-run and merge/replace. Plain secrets never export.

Module 02 implements formatVersion 1 for users/teams/agents/tools/policies/devices.
Use [API semantics](../api/control-plane-api.md) and the
[valid dry-run example](../examples/control-import.json). Exports have an optimistic
tenant revision; imports require it in both snapshot and If-Match. Unknown versions
are rejected. Credentials, identity-provider accounts, audit history and deployment
state are outside this configuration snapshot.

## Server settings

Server settings are a separate, small document (`ControlServerSettings`, formatVersion 2,
defined in `packages/contracts/schemas/v2/settings.schema.json`). The console's
**Configuration** menu edits it:

- **Server** (`#config-server`) saves the `gateway` group.
- **Device** (`#config-device`) saves the `deviceApproval` group and shows the
  `clientCheckIn` values read-only.
- **Backup/Restore** (`#config-backup`) exports and restores the whole document.

`GET /api/control/v1/settings` returns the document. Each page saves with
`PATCH /api/control/v1/settings` (`Content-Type: application/merge-patch+json`), sending
only the groups it owns; a group in the body replaces that group and the others are
unchanged. Restore uses `PUT /api/control/v1/settings` with a full document. Both writes
require `toolgate-admin`, an `Idempotency-Key` and the current settings revision in
`If-Match`; a stale revision returns `409`, and the console then loads the current
values and asks the administrator to review and retry. Restoring a file shows its
values first, and nothing changes until an administrator confirms. A formatVersion 1
file is rejected.

```json
{
  "formatVersion": 2,
  "revision": 4,
  "gateway": {"gatewayName": "ToolGate"},
  "deviceApproval": {"autoApprove": true, "durationMs": 2592000000, "ownerUserId": "admin"},
  "clientCheckIn": {"checkInMs": 2000, "jitterPct": 20, "offlineAfterMs": 120000, "source": "DEFAULT"}
}
```

| Setting | Default | Meaning |
|---|---|---|
| `gateway.gatewayName` | `TOOLGATE_GATEWAY_NAME`, else `ToolGate` | Name devices show beside this gateway's URL; sent in enrollment and check-in replies |
| `deviceApproval.autoApprove` | `false` | New enrollments are approved at once, without an administrator decision |
| `deviceApproval.durationMs` | 30 days | Connection approval length for auto-approved devices, 1 to 3650 days in milliseconds |
| `deviceApproval.ownerUserId` | none | Enabled directory user that owns auto-approved devices; required while auto-approval is on |
| `clientCheckIn` | from the deployment | Read-only. Always the running Control's values from [Client check-in](configuration.md#client-check-in); ignored in a PATCH, a restore and the startup import |

> **Security:** with auto-approval on, any device that can reach the enrollment
> endpoint receives an identity for the configured duration. Keys already registered
> are never changed. If the owner user is later disabled or removed, new enrollments
> fall back to manual approval. Every auto-approval writes an
> `ENROLLMENT_AUTO_APPROVE` audit entry next to `ENROLLMENT_CREATE`.

### Import during bring-up

At startup the control plane can seed server settings from a mounted folder and from
environment variables. Environment values override the file.

| Environment variable | Meaning |
|---|---|
| `TOOLGATE_CONFIG_IMPORT_DIRECTORY` | Folder holding an exported formatVersion 2 `server-settings.json` (its revision and `clientCheckIn` are ignored) |
| `TOOLGATE_SETTINGS_AUTO_APPROVE_DEVICES` | `true` or `false` |
| `TOOLGATE_SETTINGS_AUTO_APPROVE_DURATION_DAYS` | Days, such as `30` or `30d` |
| `TOOLGATE_SETTINGS_AUTO_APPROVE_OWNER` | Owner user ID for auto-approved devices |
| `TOOLGATE_GATEWAY_NAME` | Gateway name used until one is saved in Configuration or imported |
| `TOOLGATE_CONFIG_IMPORT_OVERWRITE` | Default `false`: import only when no settings were saved yet. `true` replaces saved settings on every start |

The import uses the tenant in `TOOLGATE_CONTROL_ENDPOINT_TENANT_ID` and writes a
`SERVER_SETTINGS_IMPORT` audit entry. An invalid file or value stops startup. The
Quickstart Compose file mounts `deploy/compose/QuickStart/config` as the import folder.
