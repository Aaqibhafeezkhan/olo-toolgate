// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Canonical TriggerDeliveryState wire values; unknown values must be rejected. */
public enum TriggerDeliveryState {
    /** Canonical Pending value. */
    Pending,
    /** Canonical InFlight value. */
    InFlight,
    /** Canonical Delivered value. */
    Delivered,
    /** Canonical RetryWait value. */
    RetryWait,
    /** Canonical Held value. */
    Held,
    /** Canonical Superseded value. */
    Superseded,
    /** Canonical DeadLettered value. */
    DeadLettered,
    /** Canonical Skipped value. */
    Skipped
}
