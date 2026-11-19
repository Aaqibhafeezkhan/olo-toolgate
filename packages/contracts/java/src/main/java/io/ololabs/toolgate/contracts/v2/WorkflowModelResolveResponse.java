// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Private Gateway response; credentials must never be forwarded to runtime workers or logged.
 *
 * @param origin canonical origin value
 * @param modelId canonical modelId value
 * @param credential canonical credential value
 * @param generation canonical generation value
 * @param deploymentId canonical deploymentId value
 * @param candidateIndex canonical candidateIndex value
 * @param candidateCount canonical candidateCount value
 * @param resultSchema canonical resultSchema value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowModelResolveResponse(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "origin", required = true) String origin,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "modelId", required = true) String modelId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "credential", required = true) String credential,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "generation", required = true) WorkflowModelGeneration generation,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deploymentId", required = true) String deploymentId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "candidateIndex", required = true) Long candidateIndex,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "candidateCount", required = true) Long candidateCount,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "resultSchema", required = true) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> resultSchema
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param origin canonical origin value
     * @param modelId canonical modelId value
     * @param credential canonical credential value
     * @param generation canonical generation value
     * @param deploymentId canonical deploymentId value
     * @param candidateIndex canonical candidateIndex value
     * @param candidateCount canonical candidateCount value
     * @param resultSchema canonical resultSchema value
     */
    public WorkflowModelResolveResponse {
        java.util.Objects.requireNonNull(origin, "origin");
        java.util.Objects.requireNonNull(modelId, "modelId");
        java.util.Objects.requireNonNull(credential, "credential");
        java.util.Objects.requireNonNull(generation, "generation");
        java.util.Objects.requireNonNull(deploymentId, "deploymentId");
        java.util.Objects.requireNonNull(candidateIndex, "candidateIndex");
        java.util.Objects.requireNonNull(candidateCount, "candidateCount");
        java.util.Objects.requireNonNull(resultSchema, "resultSchema");
        resultSchema = resultSchema == null ? null : java.util.Map.copyOf(resultSchema);
    }
}
