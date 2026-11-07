// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Instances of an assignment by phase.
 *
 * @param desired canonical desired value
 * @param pending canonical pending value
 * @param starting canonical starting value
 * @param running canonical running value
 * @param paused canonical paused value
 * @param stopped canonical stopped value
 * @param failed canonical failed value
 * @param unsupported canonical unsupported value
 * @param stale canonical stale value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerInstanceCounts(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "desired", required = true) Long desired,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "pending", required = true) Long pending,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "starting", required = true) Long starting,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "running", required = true) Long running,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "paused", required = true) Long paused,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "stopped", required = true) Long stopped,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "failed", required = true) Long failed,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "unsupported", required = true) Long unsupported,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "stale", required = true) Long stale
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param desired canonical desired value
     * @param pending canonical pending value
     * @param starting canonical starting value
     * @param running canonical running value
     * @param paused canonical paused value
     * @param stopped canonical stopped value
     * @param failed canonical failed value
     * @param unsupported canonical unsupported value
     * @param stale canonical stale value
     */
    public TriggerInstanceCounts {
        java.util.Objects.requireNonNull(desired, "desired");
        java.util.Objects.requireNonNull(pending, "pending");
        java.util.Objects.requireNonNull(starting, "starting");
        java.util.Objects.requireNonNull(running, "running");
        java.util.Objects.requireNonNull(paused, "paused");
        java.util.Objects.requireNonNull(stopped, "stopped");
        java.util.Objects.requireNonNull(failed, "failed");
        java.util.Objects.requireNonNull(unsupported, "unsupported");
        java.util.Objects.requireNonNull(stale, "stale");
    }
}
