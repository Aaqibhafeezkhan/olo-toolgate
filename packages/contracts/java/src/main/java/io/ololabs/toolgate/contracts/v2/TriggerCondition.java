// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** One instance condition.
 *
 * @param type canonical type value
 * @param status canonical status value
 * @param reason canonical reason value
 * @param message canonical message value
 * @param lastTransitionTime canonical lastTransitionTime value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerCondition(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "type", required = true) TriggerConditionType type,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "status", required = true) TriggerConditionStatus status,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "reason", required = true) String reason,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "message", required = false) String message,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "lastTransitionTime", required = true) String lastTransitionTime
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param type canonical type value
     * @param status canonical status value
     * @param reason canonical reason value
     * @param message canonical message value
     * @param lastTransitionTime canonical lastTransitionTime value
     */
    public TriggerCondition {
        java.util.Objects.requireNonNull(type, "type");
        java.util.Objects.requireNonNull(status, "status");
        java.util.Objects.requireNonNull(reason, "reason");
        java.util.Objects.requireNonNull(lastTransitionTime, "lastTransitionTime");
    }
}
