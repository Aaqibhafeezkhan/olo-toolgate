// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Authenticated worker capabilities. Control only leases runs pinned to this compiler and an installed graph digest; this grants no model or tool authority.
 *
 * @param compilerDigest canonical compilerDigest value
 * @param graphDigests canonical graphDigests value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowRunClaimRequest(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "compilerDigest", required = true) String compilerDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "graphDigests", required = true) java.util.List<String> graphDigests
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param compilerDigest canonical compilerDigest value
     * @param graphDigests canonical graphDigests value
     */
    public WorkflowRunClaimRequest {
        java.util.Objects.requireNonNull(compilerDigest, "compilerDigest");
        java.util.Objects.requireNonNull(graphDigests, "graphDigests");
        graphDigests = graphDigests == null ? null : java.util.List.copyOf(graphDigests);
    }
}
