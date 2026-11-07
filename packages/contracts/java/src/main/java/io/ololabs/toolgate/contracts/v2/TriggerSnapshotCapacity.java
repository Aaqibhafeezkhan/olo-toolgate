// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Declared SnapshotState bounds (plan 5.6).
 *
 * @param capacity canonical capacity value
 * @param maxKeyBytes canonical maxKeyBytes value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerSnapshotCapacity(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "capacity", required = true) Long capacity,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "maxKeyBytes", required = true) Long maxKeyBytes
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param capacity canonical capacity value
     * @param maxKeyBytes canonical maxKeyBytes value
     */
    public TriggerSnapshotCapacity {
        java.util.Objects.requireNonNull(capacity, "capacity");
        java.util.Objects.requireNonNull(maxKeyBytes, "maxKeyBytes");
    }
}
