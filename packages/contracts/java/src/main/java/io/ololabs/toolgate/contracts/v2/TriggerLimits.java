// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Declared per-instance limits.
 *
 * @param maxEventsPerHour canonical maxEventsPerHour value
 * @param maxEventBytes canonical maxEventBytes value
 * @param maxCheckpointBytes canonical maxCheckpointBytes value
 * @param memoryMiB canonical memoryMiB value
 * @param maxBrokerCallsPerMinute canonical maxBrokerCallsPerMinute value
 * @param stopGraceMs canonical stopGraceMs value
 * @param emitTimeoutMs canonical emitTimeoutMs value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerLimits(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "maxEventsPerHour", required = true) Long maxEventsPerHour,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "maxEventBytes", required = true) Long maxEventBytes,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "maxCheckpointBytes", required = true) Long maxCheckpointBytes,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "memoryMiB", required = false) Long memoryMiB,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "maxBrokerCallsPerMinute", required = false) Long maxBrokerCallsPerMinute,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "stopGraceMs", required = true) Long stopGraceMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "emitTimeoutMs", required = false) Long emitTimeoutMs
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param maxEventsPerHour canonical maxEventsPerHour value
     * @param maxEventBytes canonical maxEventBytes value
     * @param maxCheckpointBytes canonical maxCheckpointBytes value
     * @param memoryMiB canonical memoryMiB value
     * @param maxBrokerCallsPerMinute canonical maxBrokerCallsPerMinute value
     * @param stopGraceMs canonical stopGraceMs value
     * @param emitTimeoutMs canonical emitTimeoutMs value
     */
    public TriggerLimits {
        java.util.Objects.requireNonNull(maxEventsPerHour, "maxEventsPerHour");
        java.util.Objects.requireNonNull(maxEventBytes, "maxEventBytes");
        java.util.Objects.requireNonNull(maxCheckpointBytes, "maxCheckpointBytes");
        java.util.Objects.requireNonNull(stopGraceMs, "stopGraceMs");
    }
}
