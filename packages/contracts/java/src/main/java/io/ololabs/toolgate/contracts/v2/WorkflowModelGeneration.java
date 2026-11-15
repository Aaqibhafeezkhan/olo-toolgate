// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Explicit bounded inference parameters selected from the pinned model deployment, never from a runner request.
 *
 * @param maxOutputTokens canonical maxOutputTokens value
 * @param temperature canonical temperature value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowModelGeneration(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "maxOutputTokens", required = true) Long maxOutputTokens,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "temperature", required = true) Double temperature
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param maxOutputTokens canonical maxOutputTokens value
     * @param temperature canonical temperature value
     */
    public WorkflowModelGeneration {
        java.util.Objects.requireNonNull(maxOutputTokens, "maxOutputTokens");
        java.util.Objects.requireNonNull(temperature, "temperature");
    }
}
