// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** One instance a mutation adds, changes or removes.
 *
 * @param instanceId canonical instanceId value
 * @param executorId canonical executorId value
 * @param change canonical change value
 * @param oldDesiredState canonical oldDesiredState value
 * @param newDesiredState canonical newDesiredState value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerInstanceChange(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "instanceId", required = true) String instanceId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "executorId", required = true) String executorId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "change", required = true) TriggerInstanceChangeKind change,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "oldDesiredState", required = false) TriggerDesiredState oldDesiredState,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "newDesiredState", required = false) TriggerDesiredState newDesiredState
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param instanceId canonical instanceId value
     * @param executorId canonical executorId value
     * @param change canonical change value
     * @param oldDesiredState canonical oldDesiredState value
     * @param newDesiredState canonical newDesiredState value
     */
    public TriggerInstanceChange {
        java.util.Objects.requireNonNull(instanceId, "instanceId");
        java.util.Objects.requireNonNull(executorId, "executorId");
        java.util.Objects.requireNonNull(change, "change");
    }
}
