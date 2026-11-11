// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Profile declaration for WorkflowExecutionLimits; eligibility and configuration confer no runtime authority.
 *
 * @param maxSteps canonical maxSteps value
 * @param maxHandoffs canonical maxHandoffs value
 * @param maxDurationSeconds canonical maxDurationSeconds value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowExecutionLimits(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "maxSteps", required = true) Long maxSteps,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "maxHandoffs", required = true) Long maxHandoffs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "maxDurationSeconds", required = true) Long maxDurationSeconds
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param maxSteps canonical maxSteps value
     * @param maxHandoffs canonical maxHandoffs value
     * @param maxDurationSeconds canonical maxDurationSeconds value
     */
    public WorkflowExecutionLimits {
        java.util.Objects.requireNonNull(maxSteps, "maxSteps");
        java.util.Objects.requireNonNull(maxHandoffs, "maxHandoffs");
        java.util.Objects.requireNonNull(maxDurationSeconds, "maxDurationSeconds");
    }
}
