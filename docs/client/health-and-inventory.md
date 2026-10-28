# Health and Inventory

## Purpose

Report client version, OS/arch, runtime availability, installed package versions and health—never user file contents.

The Clients table joins these reports with the [device registry](../control-plane/device-registry.md),
including names, registered users and pending requests. Connected requires an
authenticated report within two minutes and current registry access; approval
alone is insufficient. Server-managed Quickstart devices use composition readiness.
Unknown availability is not green. Check-ins update report revision independently
of approval revision and cannot override disablement, deapproval or expiry.

## Owner power switch

The device's user may switch the client off and on locally: **Power off / Power on**
in the Windows tray (the status window shows `Power: Off`), or
`olo-toolgate-client power on|off|status` on Linux and macOS. Secured Docker devices
use the same command from the host: `docker exec <container> olo-toolgate-client power off`.

- The switch is on after installation and must persist across restarts and upgrades
  (`power.json` in the device state directory; a purge resets it to on).
- Off sends one final report with `power: OFF`, then stops check-ins for the focused
  gateway and every background connection, refuses queued and new tool requests,
  and starts no local runtime. Enrollment and keys are kept; **on** resumes without
  re-enrollment.
- Only the authenticated local IPC peer can change it. No gateway, console or
  policy reply can switch a device on.
- Control records the report: the Clients table shows **Powered off by owner**
  instead of Offline, placement skips the device at once, direct requests to it
  are refused, and the audit log records `DEVICE_POWER_OFF` / `DEVICE_POWER_ON`.
  Reports from clients without the switch carry no `power` and count as on.

Power-switch clients require Control with power-report support. Upgrade Control
before installing these clients: older closed `ClientReport` readers reject the
`power` field, even when its value is `ON`. Current Control continues to accept
older clients' reports without `power`. Downgrading Control requires rolling back
power-switch clients first; there is no power-report capability negotiation.
