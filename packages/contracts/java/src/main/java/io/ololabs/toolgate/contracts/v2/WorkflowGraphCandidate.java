// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Reviewed immutable graph candidate read by a verified tenant runtime service. Carries configuration only, never execution credentials.
 *
 * @param tenantId canonical tenantId value
 * @param profileId canonical profileId value
 * @param profileDigest canonical profileDigest value
 * @param workflowId canonical workflowId value
 * @param generation canonical generation value
 * @param graph canonical graph value
 * @param agentIds canonical agentIds value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowGraphCandidate(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tenantId", required = true) String tenantId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "profileId", required = true) String profileId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "profileDigest", required = true) String profileDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "workflowId", required = true) String workflowId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "generation", required = true) Long generation,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "graph", required = true) WorkflowGraphSelection graph,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "agentIds", required = true) java.util.List<String> agentIds
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param tenantId canonical tenantId value
     * @param profileId canonical profileId value
     * @param profileDigest canonical profileDigest value
     * @param workflowId canonical workflowId value
     * @param generation canonical generation value
     * @param graph canonical graph value
     * @param agentIds canonical agentIds value
     */
    public WorkflowGraphCandidate {
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        java.util.Objects.requireNonNull(profileId, "profileId");
        java.util.Objects.requireNonNull(profileDigest, "profileDigest");
        java.util.Objects.requireNonNull(workflowId, "workflowId");
        java.util.Objects.requireNonNull(generation, "generation");
        java.util.Objects.requireNonNull(graph, "graph");
        java.util.Objects.requireNonNull(agentIds, "agentIds");
        agentIds = agentIds == null ? null : java.util.List.copyOf(agentIds);
    }
}
