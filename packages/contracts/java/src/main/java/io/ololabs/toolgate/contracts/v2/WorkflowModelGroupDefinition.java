// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Profile declaration for WorkflowModelGroupDefinition; eligibility and configuration confer no runtime authority.
 *
 * @param id canonical id value
 * @param deploymentIds canonical deploymentIds value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowModelGroupDefinition(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "id", required = true) String id,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deploymentIds", required = true) java.util.List<String> deploymentIds
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param id canonical id value
     * @param deploymentIds canonical deploymentIds value
     */
    public WorkflowModelGroupDefinition {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(deploymentIds, "deploymentIds");
        deploymentIds = deploymentIds == null ? null : java.util.List.copyOf(deploymentIds);
    }
}
