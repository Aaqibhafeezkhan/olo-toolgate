# Devices, packages and triggers

Devices execute approved package capabilities under current Control authority.
These saved debug-installation screens illustrate setup and inspection; sample
memberships and assignments are not new-install defaults.
[Capture scope](README.md#capture-sources-and-rebuild).

## Enroll and configure a device

Review the actual destination's enrollment identity and fingerprint before
approving it. Connection approval and account/device enablement are separate
from runtime grants.

![Device enrollment](olo-2026-11/console/enroll.jpg)

The client list exposes the registered devices and their current reported state.
A connected device is not automatically authorized to run every installed tool.

![Registered clients](olo-2026-11/console/devices.jpg)

Device Groups define execution scope. Assign the groups required by the bot and
bind its Tool Groups through reviewed configuration.

![Device Group administration](olo-2026-11/console/deviceGroups.jpg)

Reported evidence is inspected separately from approval and grants; a healthy
report does not replace current authorization or establish OS attestation.

![Device evidence](olo-2026-11/console/deviceEvidence.jpg)

Package deployment reconciles desired versions with the device. Installation
does not grant a caller access to the package's tools.

![Managed package deployments](olo-2026-11/console/fleet.jpg)

The Device settings page controls the installation's allowed capabilities and
configuration. Bot import does not create filesystem permissions or host mounts.

![Installation Device settings](olo-2026-11/console/config-device.jpg)

![Animated device and package walkthrough](device-flow.gif)

Follow [device setup](../help/device.md), [enrollment controls](../control-plane/device-registry.md)
and [signed package deployment](../client/package-deployment.md) for procedures.

## Connect a trigger to a bot

Trigger package references identify the source capability. The managed-file
examples do not grant arbitrary host logs, filesystems or provider access.

![Bot trigger packages](olo-2026-11/console/workflow-trigger-packages.jpg)

A dispatch mapping connects an authorized source to the intended workflow.
Event content cannot select a different actor or more privileged bot.

![Trigger dispatch mappings](olo-2026-11/console/workflow-dispatch.jpg)

The assignment screen exposes package versions, desired/running state and instance
actions. This administration capture is not proof of subscriber authority or of
every proposed server/PUSH connector.

![Package-backed trigger assignments](olo-2026-11/triggers.png)

Use [trigger management](../control-plane/trigger-assignments.md),
[available examples](../architecture/trigger-package-catalog.md) and
[trigger architecture](../architecture/triggers.md) for current support and limits.

[All illustrated capabilities](README.md) · [Back to OLO](../../README.md#what-olo-supports)
