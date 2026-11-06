// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Trigger block of every executor check-in (plan 8.2, 8.5).
 *
 * @param observedGeneration canonical observedGeneration value
 * @param snapshot canonical snapshot value
 * @param checkpoints canonical checkpoints value
 * @param events canonical events value
 * @param pending canonical pending value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerSync(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "observedGeneration", required = true) Long observedGeneration,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "snapshot", required = false) TriggerSnapshotPage snapshot,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "checkpoints", required = false) java.util.List<TriggerCheckpointReport> checkpoints,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "events", required = false) java.util.List<TriggerOutboxRecord> events,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "pending", required = true) Long pending
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param observedGeneration canonical observedGeneration value
     * @param snapshot canonical snapshot value
     * @param checkpoints canonical checkpoints value
     * @param events canonical events value
     * @param pending canonical pending value
     */
    public TriggerSync {
        java.util.Objects.requireNonNull(observedGeneration, "observedGeneration");
        checkpoints = checkpoints == null ? null : java.util.List.copyOf(checkpoints);
        events = events == null ? null : java.util.List.copyOf(events);
        java.util.Objects.requireNonNull(pending, "pending");
    }
}
