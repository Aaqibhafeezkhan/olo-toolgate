// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Gateway-only resolution of one authorized inference candidate, with current authority checked on every attempt.
 *
 * @param runId canonical runId value
 * @param stepToken canonical stepToken value
 * @param candidateIndex canonical candidateIndex value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowModelResolveRequest(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "runId", required = true) String runId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "stepToken", required = true) String stepToken,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "candidateIndex", required = true) Long candidateIndex
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param runId canonical runId value
     * @param stepToken canonical stepToken value
     * @param candidateIndex canonical candidateIndex value
     */
    public WorkflowModelResolveRequest {
        java.util.Objects.requireNonNull(runId, "runId");
        java.util.Objects.requireNonNull(stepToken, "stepToken");
        java.util.Objects.requireNonNull(candidateIndex, "candidateIndex");
    }
}
