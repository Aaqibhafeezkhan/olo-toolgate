// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Profile declaration for WorkflowConfiguration; eligibility and configuration confer no runtime authority.
 *
 * @param workflowGroups canonical workflowGroups value
 * @param modelGroups canonical modelGroups value
 * @param agentDefinitions canonical agentDefinitions value
 * @param workflows canonical workflows value
 * @param dispatchBindings canonical dispatchBindings value
 * @param modelDeployments canonical modelDeployments value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowConfiguration(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "workflowGroups", required = true) java.util.List<WorkflowGroupDefinition> workflowGroups,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "modelGroups", required = true) java.util.List<WorkflowModelGroupDefinition> modelGroups,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "agentDefinitions", required = true) java.util.List<WorkflowAgentDefinition> agentDefinitions,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "workflows", required = true) java.util.List<WorkflowDefinition> workflows,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "dispatchBindings", required = true) java.util.List<WorkflowDispatchBinding> dispatchBindings,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "modelDeployments", required = true) java.util.List<WorkflowModelDeployment> modelDeployments
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param workflowGroups canonical workflowGroups value
     * @param modelGroups canonical modelGroups value
     * @param agentDefinitions canonical agentDefinitions value
     * @param workflows canonical workflows value
     * @param dispatchBindings canonical dispatchBindings value
     * @param modelDeployments canonical modelDeployments value
     */
    public WorkflowConfiguration {
        java.util.Objects.requireNonNull(workflowGroups, "workflowGroups");
        workflowGroups = workflowGroups == null ? null : java.util.List.copyOf(workflowGroups);
        java.util.Objects.requireNonNull(modelGroups, "modelGroups");
        modelGroups = modelGroups == null ? null : java.util.List.copyOf(modelGroups);
        java.util.Objects.requireNonNull(agentDefinitions, "agentDefinitions");
        agentDefinitions = agentDefinitions == null ? null : java.util.List.copyOf(agentDefinitions);
        java.util.Objects.requireNonNull(workflows, "workflows");
        workflows = workflows == null ? null : java.util.List.copyOf(workflows);
        java.util.Objects.requireNonNull(dispatchBindings, "dispatchBindings");
        dispatchBindings = dispatchBindings == null ? null : java.util.List.copyOf(dispatchBindings);
        java.util.Objects.requireNonNull(modelDeployments, "modelDeployments");
        modelDeployments = modelDeployments == null ? null : java.util.List.copyOf(modelDeployments);
    }
}
