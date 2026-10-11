# Console Administration menu

## Purpose

This document specifies the **Administration** menu of the administration console: a
bottom sidebar group with Server, Device, Backup/Restore and About pages, and the field-level
settings save behind them.

> **Status: implemented in milestone M1; renamed in RC.** See [ADR 022](../adr/022-super-administrator-review-exception.md) for the current administration exception and
> [ADR 021](../adr/021-client-checkin-configuration-menu.md). Source:
> [Tool SDK plan §18](../sdk-plan/plan.md).

## Before M1

- The `configuration` route ("Configuration reviews") sat in the **Audit** group and
  rendered, above the review queue, the server settings form and the configuration
  import/export.
- The settings form saved the whole formatVersion 1 record with
  `PUT /api/control/v1/settings`.

## Layout

The console has a bottom sidebar group **Administration** with four pages.
Server, Device and Backup/Restore moved from Audit > Configuration reviews;
About now belongs to Administration. Fresh browser history leaves every group collapsed.

| Page | Route | Contents | Moved from |
|---|---|---|---|
| Server | `#config-server` | **Gateway**: Gateway name (default from `TOOLGATE_GATEWAY_NAME`) | `ServerSettings` Gateway fieldset |
| Device | `#config-device` | **Device approval**: auto-approve, duration, owner. **Check-in**: effective values, read-only | `ServerSettings` Device approval fieldset; check-in is new |
| Backup/Restore | `#config-backup` | **Import and export configuration**, plus the server settings file import/export | `ConfigurationTransfer`, and the settings import/export from `ServerSettings` |
| About | `#about` | Application version, build and license | Standalone About navigation |

```text
Sidebar                      Administration > Server (#config-server)
-------------------------    -------------------------------------------
Overview                     [Gateway]
> Audit                        Gateway name  [______________]
    Audit log                  (default from TOOLGATE_GATEWAY_NAME)
    Approvals                                               [Save]
    Configuration reviews
    Effect outcomes          Administration > Device (#config-device)
    Access simulation        -------------------------------------------
> Tools                      [Device approval]
> Devices                      [x] Auto approve devices
> Users                        Auto approve duration (days) [___]
> Agents                       Owner user ID for auto-approved devices [___]
                                                            [Save]
> Administration             [Check-in]  (read-only, set by environment)
    Server                     Check-in interval       2000 ms
    Device                     Jitter                  20 %
    Backup/Restore             Offline after           120000 ms
    About

                             Administration > Backup/Restore (#config-backup)
                             -------------------------------------------
                             [Import and export configuration]
                               [Export complete configuration]
                               Import mode  [Replace | Merge]
                               Files [...]  [Preview import]
                             [Server settings file]
                               [Export server settings]
                               Server settings JSON file [...]
```

## Rules

- Audit > Configuration reviews (`#config-reviews`) keeps only the review queue.
- Old `#configuration` links redirect to `#config-server`. Links that name a review
  (`#configuration?id=<id>`) redirect to `#config-reviews?id=<id>` and open that review.
  New draft links (`apps/admin-ui/src/Failure.tsx`) use `#config-reviews?id=<id>`.
- The Check-in fieldset is read-only. Its values are environment-owned and come from
  the variables in [Client check-in](configuration.md#client-check-in).
  The page shows the effective values and says they are set by the deployment.
- Server and Device each edit part of one `ControlServerSettings` record. Each page
  sends only its own group.

## Saving

Saving uses a merge patch of the page's own group:

```text
PATCH /api/control/v1/settings
Content-Type: application/merge-patch+json
If-Match: "<revision>"
Idempotency-Key: <key>

{"gateway": {"gatewayName": "Production gateway"}}
```

- `If-Match` carries the quoted revision the page loaded.
- A group in the body replaces that whole group, so clearing the optional owner means
  leaving it out. Groups not in the body are unchanged.
- A stale revision returns `409`, as the PUT does. The UI says the settings changed
  elsewhere, loads the current values and asks the administrator to review and retry,
  instead of overwriting them.
- `ControlServerSettings.formatVersion` is 2. Records saved before M1 reset to the
  defaults, and formatVersion 1 files are rejected.
- Environment-owned values (the check-in values) are rejected in a PATCH body and
  ignored in a restore and in the startup configuration import.
- The full-record `PUT /api/control/v1/settings` is used by Backup/Restore, with strict
  compare-and-swap on the revision.

## Failure Behavior

| Case | Result |
|---|---|
| Revision changed since the page loaded | `409`; the UI loads the current values and asks the administrator to review and retry |
| Retried save with the same `Idempotency-Key` | Same result as the first attempt; no second change |

## Later items

Later additions to this menu, not part of M1:

- trusted issuers and introspection;
- Tool Host pools;
- egress approvals;
- kill switch;
- allowed runtime tiers;
- compatibility profiles.

## Related Docs

- [Admin UI](admin-ui.md)
- [Control Plane configuration](configuration.md)
- [Import and export](import-export.md)
- [Tool SDK plan](../sdk-plan/plan.md)
