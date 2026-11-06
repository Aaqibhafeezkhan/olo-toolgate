// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Outbox depth for one instance.
 *
 * @param depth canonical depth value
 * @param oldestAgeMs canonical oldestAgeMs value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerOutboxStatus(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "depth", required = true) Long depth,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "oldestAgeMs", required = true) Long oldestAgeMs
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param depth canonical depth value
     * @param oldestAgeMs canonical oldestAgeMs value
     */
    public TriggerOutboxStatus {
        java.util.Objects.requireNonNull(depth, "depth");
        java.util.Objects.requireNonNull(oldestAgeMs, "oldestAgeMs");
    }
}
