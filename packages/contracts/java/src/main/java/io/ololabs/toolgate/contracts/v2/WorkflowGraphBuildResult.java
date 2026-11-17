// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Compilation receipt; this neither publishes configuration nor grants runtime authority.
 *
 * @param tenantId canonical tenantId value
 * @param profileId canonical profileId value
 * @param profileDigest canonical profileDigest value
 * @param workflowId canonical workflowId value
 * @param generation canonical generation value
 * @param specificationDigest canonical specificationDigest value
 * @param compilerDigest canonical compilerDigest value
 * @param features canonical features value
 * @param state canonical state value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowGraphBuildResult(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tenantId", required = true) String tenantId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "profileId", required = true) String profileId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "profileDigest", required = true) String profileDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "workflowId", required = true) String workflowId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "generation", required = true) Long generation,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "specificationDigest", required = true) String specificationDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "compilerDigest", required = true) String compilerDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "features", required = true) java.util.List<String> features,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "state", required = true) String state
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param tenantId canonical tenantId value
     * @param profileId canonical profileId value
     * @param profileDigest canonical profileDigest value
     * @param workflowId canonical workflowId value
     * @param generation canonical generation value
     * @param specificationDigest canonical specificationDigest value
     * @param compilerDigest canonical compilerDigest value
     * @param features canonical features value
     * @param state canonical state value
     */
    public WorkflowGraphBuildResult {
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        java.util.Objects.requireNonNull(profileId, "profileId");
        java.util.Objects.requireNonNull(profileDigest, "profileDigest");
        java.util.Objects.requireNonNull(workflowId, "workflowId");
        java.util.Objects.requireNonNull(generation, "generation");
        java.util.Objects.requireNonNull(specificationDigest, "specificationDigest");
        java.util.Objects.requireNonNull(compilerDigest, "compilerDigest");
        java.util.Objects.requireNonNull(features, "features");
        features = features == null ? null : java.util.List.copyOf(features);
        java.util.Objects.requireNonNull(state, "state");
        if (state != null && !java.util.Objects.equals(state, "READY")) throw new IllegalArgumentException("state must be READY");
    }
}
