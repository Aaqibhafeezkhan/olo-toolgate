// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Structural status for one readable trigger assignment, without configuration or source event payloads.
 *
 * @param assignmentId canonical assignmentId value
 * @param status canonical status value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ApplicationTriggerStatus(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "assignmentId", required = true) String assignmentId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "status", required = true) TriggerAssignmentStatus status
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param assignmentId canonical assignmentId value
     * @param status canonical status value
     */
    public ApplicationTriggerStatus {
        java.util.Objects.requireNonNull(assignmentId, "assignmentId");
        java.util.Objects.requireNonNull(status, "status");
    }
}
