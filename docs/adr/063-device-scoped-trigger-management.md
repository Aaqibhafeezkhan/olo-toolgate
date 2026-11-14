<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 063: Device-scoped trigger management

Status: Proposed.

The console discovers trigger definitions for a selected device. Control checks
the caller's trigger-read permission on that device and returns client trigger
exports from the package versions assigned to it. The package descriptor
provides the trigger identifier, configuration schema, and event metadata.
Control validates the submitted assignment and configuration.

The trigger read, manage, and subscribe permissions can be scoped to devices
owned by the caller. Control checks the relationship on each explicitly
targeted device. A device ownership rule grants no authority over a shared
device group. A transfer removes the former owner's device-scoped access.
