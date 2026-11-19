// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Ordered model candidates; selection intersects this order with the pinned workflow ceiling. Only provider inference failures permit fallback; effects are never retried.
 *
 * @param id canonical id value
 * @param deploymentIds canonical deploymentIds value
 * @param standby canonical standby value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowModelGroupDefinition(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "id", required = true) String id,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deploymentIds", required = true) java.util.List<String> deploymentIds,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "standby", required = false) Boolean standby
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param id canonical id value
     * @param deploymentIds canonical deploymentIds value
     * @param standby canonical standby value
     */
    public WorkflowModelGroupDefinition {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(deploymentIds, "deploymentIds");
        deploymentIds = deploymentIds == null ? null : java.util.List.copyOf(deploymentIds);
    }
}
