# Workspace administration

The console groups orchestration, execution governance and installation
administration in one workspace. These saved debug-installation screenshots
illustrate navigation and configuration; their sample users and memberships
are not fresh-install defaults.
[Capture scope](README.md#capture-sources-and-rebuild).

## Sign in and orient yourself

Use the installation's private administrator credentials. A successful login
does not grant runtime tools to every user or bot.

![OLO sign-in](olo-2026-11/console/sign-in.jpg)

The overview links the workspace's main sections. Light and dark themes present
the same underlying installation and authority boundaries.

![Workspace overview](olo-2026-11/console/overview.jpg)

![Workspace overview in light theme](olo-2026-11/console/overview-light.jpg)

See [first login](../getting-started/first-login.md).

## Manage people and authority

Users join Teams; roles supply scoped administration and runtime responsibilities.
Enabling a user, assigning a management role and granting tool execution are
different actions.

![Users](olo-2026-11/console/users.jpg)

![Teams](olo-2026-11/console/teams.jpg)

![Typed roles](olo-2026-11/console/roles.jpg)

Identity bindings and delegations preserve the distinction between a human
subject and an agent acting on their behalf. Ownership alone is not delegation.

![Human identity bindings](olo-2026-11/console/identityBindings.jpg)

![Human delegations](olo-2026-11/console/delegations.jpg)

Read [user roles](../security/user-roles.md),
[console permissions](../security/console-permissions.md) and
[group access control](../security/enterprise-access-control.md).

## Configure the installation

Server and Device settings are installation-owned. Importing a bot does not
silently change its filesystem roots, host mounts or infrastructure.

![Server settings](olo-2026-11/console/config-server.jpg)

![Device settings](olo-2026-11/console/config-device.jpg)

Backup/restore and vault custody require their own protected procedures; do not
treat a settings screen as permission to copy credentials between installations.

![Backup and restore settings](olo-2026-11/console/config-backup.jpg)

![Local vault configuration](olo-2026-11/console/local.jpg)

The About screen identifies the application version for support and investigation.

![About OLO](olo-2026-11/console/about.jpg)

![Animated administration walkthrough](console-administration.gif)

Use [configuration settings](../control-plane/configuration-menu.md),
[reviewed recovery](../enterprise-access-control/operations.md) and
[tenant vault custody](../control-plane/tenant-vault.md) for operational instructions.

[Overview video and more media](README.md) · [Back to OLO](../../README.md#what-olo-supports)
