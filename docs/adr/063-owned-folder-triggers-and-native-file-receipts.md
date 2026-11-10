<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 063: Owned folder triggers and native file receipts

Status: In progress; acceptance requires the checks below.

## Context

The current trigger slice delivers events but does not offer a device-owner
configuration screen. Relationship-scoped management rules accept only device
permissions, while trigger assignments require group-level authority. Native
HotFolder calls also return an unqualified operation outcome even when the
device returns file content. Neither granting every visitor group authority nor
ignoring that outcome is an acceptable implementation of a personal-folder demo.

## Decision

Allow `trigger.read`, `trigger.manage` and `trigger.subscribe` in an `OWNED`
device rule. Require the authenticated owner's relationship on every explicitly
targeted device. Such rules confer no authority over a whole device group. A
transfer of device ownership immediately removes the former owner's authority.
The console offers a single-device folder assignment with a relative folder and
extension inside the device's configured files area. Absolute paths, traversal,
and arbitrary host filesystem access are outside this contract.

Native execution confirmation must come from the confined file broker after
the worker has exited, rather than from the package's result body. The broker
must bind its observations to the consumed invocation, package and runtime,
record the actual file operations, and persist completion with the existing
device-authenticated result outbox. Control must independently bind evidence
to admitted authority, the current tenant/device and the exact attempt. An
unqualified runtime, missing evidence, incomplete operation, interrupted process
or uncertain delivery remains unknown and must not be retried automatically.
No synthetic human reconciliation or unconditional success conversion is used.

This is a scoped implementation of the native target boundary, not completion
of the wider execution or trigger roadmap. Only executed evidence earns progress.

## Acceptance

- Owner creates a trigger for one device; another device and a shared-group
  target are denied, including a widening patch and ownership transfer.
- The user selects a folder in the console and the listener receives its event.
- A dropped file is read and moved with confirmed, correctly graded outcomes.
- Forged/stale/cross-device completion evidence, worker-only output, crashes and
  lost responses never become confirmed success or an automatic second write.
- Fresh SQLite and PostgreSQL checks, UI tests, the real composed flow, and the
  affected component checks execute before delivery.

## Implementation evidence

The owner-bound assignment/subscription test currently passes on SQLite. The
native receipt boundary and composed acceptance remain under implementation.
