// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Profile declaration for WorkflowContextSource; eligibility and configuration confer no runtime authority.
 *
 * @param toolId canonical toolId value
 * @param arguments canonical arguments value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowContextSource(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toolId", required = true) String toolId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "arguments", required = true) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> arguments
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param toolId canonical toolId value
     * @param arguments canonical arguments value
     */
    public WorkflowContextSource {
        java.util.Objects.requireNonNull(toolId, "toolId");
        java.util.Objects.requireNonNull(arguments, "arguments");
        arguments = arguments == null ? null : java.util.Map.copyOf(arguments);
    }
}
