// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Result of an assignment mutation, or of its dry run.
 *
 * @param assignment canonical assignment value
 * @param dryRun canonical dryRun value
 * @param changes canonical changes value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerAssignmentResult(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "assignment", required = true) TriggerAssignment assignment,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "dryRun", required = true) Boolean dryRun,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "changes", required = true) java.util.List<TriggerInstanceChange> changes
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param assignment canonical assignment value
     * @param dryRun canonical dryRun value
     * @param changes canonical changes value
     */
    public TriggerAssignmentResult {
        java.util.Objects.requireNonNull(assignment, "assignment");
        java.util.Objects.requireNonNull(dryRun, "dryRun");
        java.util.Objects.requireNonNull(changes, "changes");
        changes = changes == null ? null : java.util.List.copyOf(changes);
    }
}
