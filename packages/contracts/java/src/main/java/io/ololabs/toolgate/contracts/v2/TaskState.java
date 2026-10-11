// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Canonical TaskState wire values; unknown values must be rejected. */
public enum TaskState {
    /** Canonical ACCEPTED value. */
    ACCEPTED,
    /** Canonical AWAITING_APPROVAL value. */
    AWAITING_APPROVAL,
    /** Canonical QUEUED value. */
    QUEUED,
    /** Canonical DISPATCHED value. */
    DISPATCHED,
    /** Canonical RUNNING value. */
    RUNNING,
    /** Canonical INPUT_REQUIRED value. */
    INPUT_REQUIRED,
    /** Canonical CANCEL_REQUESTED value. */
    CANCEL_REQUESTED,
    /** Canonical COMPLETED value. */
    COMPLETED,
    /** Canonical FAILED value. */
    FAILED,
    /** Canonical REJECTED value. */
    REJECTED,
    /** Canonical EXPIRED value. */
    EXPIRED,
    /** Canonical CANCELLED value. */
    CANCELLED,
    /** Canonical OUTCOME_UNKNOWN value. */
    OUTCOME_UNKNOWN
}
