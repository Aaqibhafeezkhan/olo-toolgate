// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Profile declaration for WorkflowAgentDefinition; eligibility and configuration confer no runtime authority.
 *
 * @param agentId canonical agentId value
 * @param modelGroupId canonical modelGroupId value
 * @param systemPrompt canonical systemPrompt value
 * @param contextSources canonical contextSources value
 * @param outputSchema canonical outputSchema value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowAgentDefinition(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "agentId", required = true) String agentId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "modelGroupId", required = true) String modelGroupId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "systemPrompt", required = true) String systemPrompt,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "contextSources", required = true) java.util.List<WorkflowContextSource> contextSources,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "outputSchema", required = true) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> outputSchema
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param agentId canonical agentId value
     * @param modelGroupId canonical modelGroupId value
     * @param systemPrompt canonical systemPrompt value
     * @param contextSources canonical contextSources value
     * @param outputSchema canonical outputSchema value
     */
    public WorkflowAgentDefinition {
        java.util.Objects.requireNonNull(agentId, "agentId");
        java.util.Objects.requireNonNull(modelGroupId, "modelGroupId");
        java.util.Objects.requireNonNull(systemPrompt, "systemPrompt");
        java.util.Objects.requireNonNull(contextSources, "contextSources");
        contextSources = contextSources == null ? null : java.util.List.copyOf(contextSources);
        java.util.Objects.requireNonNull(outputSchema, "outputSchema");
        outputSchema = outputSchema == null ? null : java.util.Map.copyOf(outputSchema);
    }
}
