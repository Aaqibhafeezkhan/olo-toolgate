// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Canonical TriggerRevocationReason wire values; unknown values must be rejected. */
public enum TriggerRevocationReason {
    /** Canonical AssignmentStopped value. */
    AssignmentStopped,
    /** Canonical AssignmentRemoved value. */
    AssignmentRemoved,
    /** Canonical PackageSuspended value. */
    PackageSuspended,
    /** Canonical KillSwitch value. */
    KillSwitch,
    /** Canonical ExecutorDeregistered value. */
    ExecutorDeregistered,
    /** Canonical DeviceRevoked value. */
    DeviceRevoked,
    /** Canonical Compromised value. */
    Compromised,
    /** Canonical Reassigned value. */
    Reassigned
}
