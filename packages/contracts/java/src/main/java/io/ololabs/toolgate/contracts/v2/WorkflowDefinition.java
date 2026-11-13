// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Profile declaration for WorkflowDefinition; eligibility and configuration confer no runtime authority.
 *
 * @param id canonical id value
 * @param versionId canonical versionId value
 * @param workflowGroupId canonical workflowGroupId value
 * @param modelGroupId canonical modelGroupId value
 * @param systemPrompt canonical systemPrompt value
 * @param contextSources canonical contextSources value
 * @param parameterSchema canonical parameterSchema value
 * @param resultSchema canonical resultSchema value
 * @param limits canonical limits value
 * @param agents canonical agents value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowDefinition(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "id", required = true) String id,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "versionId", required = true) String versionId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "workflowGroupId", required = true) String workflowGroupId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "modelGroupId", required = true) String modelGroupId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "systemPrompt", required = true) String systemPrompt,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "contextSources", required = true) java.util.List<WorkflowContextSource> contextSources,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "parameterSchema", required = true) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> parameterSchema,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "resultSchema", required = true) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> resultSchema,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "limits", required = true) WorkflowExecutionLimits limits,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "agents", required = true) java.util.List<WorkflowAgentBinding> agents
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param id canonical id value
     * @param versionId canonical versionId value
     * @param workflowGroupId canonical workflowGroupId value
     * @param modelGroupId canonical modelGroupId value
     * @param systemPrompt canonical systemPrompt value
     * @param contextSources canonical contextSources value
     * @param parameterSchema canonical parameterSchema value
     * @param resultSchema canonical resultSchema value
     * @param limits canonical limits value
     * @param agents canonical agents value
     */
    public WorkflowDefinition {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(versionId, "versionId");
        java.util.Objects.requireNonNull(workflowGroupId, "workflowGroupId");
        java.util.Objects.requireNonNull(modelGroupId, "modelGroupId");
        java.util.Objects.requireNonNull(systemPrompt, "systemPrompt");
        java.util.Objects.requireNonNull(contextSources, "contextSources");
        contextSources = contextSources == null ? null : java.util.List.copyOf(contextSources);
        java.util.Objects.requireNonNull(parameterSchema, "parameterSchema");
        parameterSchema = parameterSchema == null ? null : java.util.Map.copyOf(parameterSchema);
        java.util.Objects.requireNonNull(resultSchema, "resultSchema");
        resultSchema = resultSchema == null ? null : java.util.Map.copyOf(resultSchema);
        java.util.Objects.requireNonNull(limits, "limits");
        java.util.Objects.requireNonNull(agents, "agents");
        agents = agents == null ? null : java.util.List.copyOf(agents);
    }
}
