// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** An explicitly assigned model deployment. Only server-owned TLS origin, model name and vault reference are configuration; no provider key reaches a runner.
 *
 * @param id canonical id value
 * @param origin canonical origin value
 * @param modelId canonical modelId value
 * @param secretName canonical secretName value
 * @param toolGroupId canonical toolGroupId value
 * @param deviceGroupId canonical deviceGroupId value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowModelDeployment(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "id", required = true) String id,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "origin", required = true) String origin,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "modelId", required = true) String modelId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "secretName", required = true) String secretName,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toolGroupId", required = true) String toolGroupId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deviceGroupId", required = true) String deviceGroupId
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param id canonical id value
     * @param origin canonical origin value
     * @param modelId canonical modelId value
     * @param secretName canonical secretName value
     * @param toolGroupId canonical toolGroupId value
     * @param deviceGroupId canonical deviceGroupId value
     */
    public WorkflowModelDeployment {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(origin, "origin");
        java.util.Objects.requireNonNull(modelId, "modelId");
        java.util.Objects.requireNonNull(secretName, "secretName");
        java.util.Objects.requireNonNull(toolGroupId, "toolGroupId");
        java.util.Objects.requireNonNull(deviceGroupId, "deviceGroupId");
    }
}
