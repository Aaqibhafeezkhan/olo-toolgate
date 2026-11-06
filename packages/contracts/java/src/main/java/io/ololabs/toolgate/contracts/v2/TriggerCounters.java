// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Monotonic counters for the current process lifetime of one instance.
 *
 * @param emitted canonical emitted value
 * @param rejected canonical rejected value
 * @param dropped canonical dropped value
 * @param emitTimeouts canonical emitTimeouts value
 * @param restarts canonical restarts value
 * @param brokerCalls canonical brokerCalls value
 * @param possibleLossOnCrash canonical possibleLossOnCrash value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerCounters(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "emitted", required = true) Long emitted,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "rejected", required = true) Long rejected,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "dropped", required = true) Long dropped,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "emitTimeouts", required = false) Long emitTimeouts,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "restarts", required = true) Long restarts,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "brokerCalls", required = false) Long brokerCalls,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "possibleLossOnCrash", required = false) Long possibleLossOnCrash
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param emitted canonical emitted value
     * @param rejected canonical rejected value
     * @param dropped canonical dropped value
     * @param emitTimeouts canonical emitTimeouts value
     * @param restarts canonical restarts value
     * @param brokerCalls canonical brokerCalls value
     * @param possibleLossOnCrash canonical possibleLossOnCrash value
     */
    public TriggerCounters {
        java.util.Objects.requireNonNull(emitted, "emitted");
        java.util.Objects.requireNonNull(rejected, "rejected");
        java.util.Objects.requireNonNull(dropped, "dropped");
        java.util.Objects.requireNonNull(restarts, "restarts");
    }
}
