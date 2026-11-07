// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Observed assignment status.
 *
 * @param observedGeneration canonical observedGeneration value
 * @param instances canonical instances value
 * @param conditions canonical conditions value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerAssignmentStatus(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "observedGeneration", required = true) Long observedGeneration,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "instances", required = true) TriggerInstanceCounts instances,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "conditions", required = true) java.util.List<TriggerAssignmentCondition> conditions
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param observedGeneration canonical observedGeneration value
     * @param instances canonical instances value
     * @param conditions canonical conditions value
     */
    public TriggerAssignmentStatus {
        java.util.Objects.requireNonNull(observedGeneration, "observedGeneration");
        java.util.Objects.requireNonNull(instances, "instances");
        java.util.Objects.requireNonNull(conditions, "conditions");
        conditions = conditions == null ? null : java.util.List.copyOf(conditions);
    }
}
