// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Canonical ReservationOutcome wire values; unknown values must be rejected. */
public enum ReservationOutcome {
    /** Canonical NEW value. */
    NEW,
    /** Canonical EXISTING value. */
    EXISTING,
    /** Canonical IDEMPOTENCY_CONFLICT value. */
    IDEMPOTENCY_CONFLICT,
    /** Canonical IDEMPOTENCY_KEY_IN_USE value. */
    IDEMPOTENCY_KEY_IN_USE,
    /** Canonical EXPIRED_KEY value. */
    EXPIRED_KEY
}
