// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Control-authorized immutable candidate reference. Contains no arbitrary Python, paths, secrets or live clients.
 *
 * @param tenantId canonical tenantId value
 * @param profileId canonical profileId value
 * @param profileDigest canonical profileDigest value
 * @param workflowId canonical workflowId value
 * @param expectedGeneration canonical expectedGeneration value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowGraphBuildRequest(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tenantId", required = true) String tenantId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "profileId", required = true) String profileId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "profileDigest", required = true) String profileDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "workflowId", required = true) String workflowId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "expectedGeneration", required = true) Long expectedGeneration
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param tenantId canonical tenantId value
     * @param profileId canonical profileId value
     * @param profileDigest canonical profileDigest value
     * @param workflowId canonical workflowId value
     * @param expectedGeneration canonical expectedGeneration value
     */
    public WorkflowGraphBuildRequest {
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        java.util.Objects.requireNonNull(profileId, "profileId");
        java.util.Objects.requireNonNull(profileDigest, "profileDigest");
        java.util.Objects.requireNonNull(workflowId, "workflowId");
        java.util.Objects.requireNonNull(expectedGeneration, "expectedGeneration");
    }
}
