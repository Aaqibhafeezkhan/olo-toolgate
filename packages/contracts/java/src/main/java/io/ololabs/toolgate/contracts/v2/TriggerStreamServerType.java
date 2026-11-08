// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Canonical TriggerStreamServerType wire values; unknown values must be rejected. */
public enum TriggerStreamServerType {
    /** Canonical subscribed value. */
    subscribed,
    /** Canonical event value. */
    event,
    /** Canonical gap value. */
    gap,
    /** Canonical revoked value. */
    revoked,
    /** Canonical error value. */
    error
}
