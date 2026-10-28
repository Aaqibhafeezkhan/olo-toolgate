<!-- Copyright 2026 OLO Labs -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# Release initial configuration

`releases.json` maps each application release to a versioned bundle directory.
The shared source baseline uses `standard-v1/`. Build preparation derives the
application version from [version.json](../../version.json) and adds its mapping
to the selected bundle. New releases can reuse it or select another bundle.
Missing mappings fail the build instead of choosing another release's defaults.

| File | Editable initial configuration |
| --- | --- |
| `manifest.json` | Bundle identity, ordered file list, read/write action classifications |
| `groups.json` | Default and standard Teams, Agent, Tool, and Device Groups |
| `roles.json` | HUMAN, ACTOR_SERVICE, and MANAGEMENT roles |
| `grants.json` | Human grants and independent service/capability grants |
| `policies.json` | Group ALLOW policies and sensitive-operation review |
| `bindings.json` | Tool Group → Device Group boundaries and approved package digests |

Edit these source files directly. Startup never regenerates or overwrites them.
To author defaults in a NEW directory, use
`python tools/access/presets.py --write-defaults config/initial/new-bundle`.
This command refuses to overwrite an existing directory. Copying an existing bundle
and editing it is also supported. Update `releases.json` to select the release's bundle.

The release embeds its bundle in Control and the console. Fresh installation loads
it once after verifying the installation review. Initial groups must be empty;
administrator membership comes from the installation identity packet separately.
Upgrades and restarts preserve stored records and never restore grants or reset edits.

Configuration → **Export complete configuration** downloads the entire tenant access
snapshot, including all entities, memberships, roles, grants, policies, extractors,
identity bindings, device evidence, and execution bindings. Edit that JSON, select it
for import, preview the diff, and create the reviewed import. **Replace** includes
removals; **Merge** retains omitted records. Two independent reviews and optimistic
revision checks remain required. Use a fresh export after a revision conflict.
Live jobs, audit history, passwords, private keys, and vault secret values remain
separate runtime/custody state and are not access-configuration exports.

For a bundle update, select its collection files and choose **Merge**. A manifest is
optional. Bundle imports preserve established memberships. Use a complete snapshot
or membership UI to intentionally change memberships. Review the full diff first.
Complete imports retain current verified login activity counters; exported telemetry
cannot overwrite newer observations. Credential and session epoch rollback remains rejected.

| Level | Resource actions | Console management |
| --- | --- | --- |
| ReadOnly | Reads, listings, inspection, search, fixed pure transformations | Read, audit, simulate, export |
| ReadAndWrite | ReadOnly plus write, append, mkdir, copy, move, create, update, delete | ReadOnly plus create, update, publish, attest, build |
| Admin | All explicitly bound actions | Administration except external recovery/Super Admin elevation |

Action names are ToolGate's explicit taxonomy, not an industry-wide action-name
standard. Review new tools' semantics. Unclassified actions require an Admin resource
binding. The lower tool/device level constrains each binding; complete actor grants
constrain it again. Disabled identities/groups, offline/unapproved devices, missing
package pins, stale credentials, and incomplete grants still deny access. Multiple
actor/device memberships permit the union of independently complete paths; each
tool belongs to exactly one group.

The preset runtime grants and grantable ceilings target the three standard Tool
and Device Groups. Custom groups need explicit reviewed scopes. Group allocation,
role activation, and account activation check inherited rights; an administrator
cannot confer rights above their own ceilings or elevate someone to Super Admin.

`execute`, `shell`, `install`, and `deploy` require `AdminTeam` runtime review.
ALLOW never creates a grant. Default groups prevent orphans and grant no runtime
access. Presets do not approve devices, enable users, enroll agents, grant vault-secret
access, or approve package digests.

Quickstart creates disabled `test-readonly`, `test-readwrite`, and `test-admin` users
with unique generated bootstrap credentials at protected
`/data/bootstrap-password-<username>` files. Explicit local debug provisioning can
enable them, assign Teams through review, and complete first-login password changes.
Production has no seeded shared test passwords.
