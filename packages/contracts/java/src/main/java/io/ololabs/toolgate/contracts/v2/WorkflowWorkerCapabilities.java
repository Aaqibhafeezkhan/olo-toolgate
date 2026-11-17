// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Authenticated worker capabilities. Control only leases runs pinned to this compiler and an installed graph digest; this grants no model or tool authority.
 *
 * @param compilerDigest canonical compilerDigest value
 * @param graphDigests canonical graphDigests value
 * @param instanceId canonical instanceId value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowWorkerCapabilities(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "compilerDigest", required = true) String compilerDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "graphDigests", required = true) java.util.List<String> graphDigests,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "instanceId", required = true) String instanceId
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param compilerDigest canonical compilerDigest value
     * @param graphDigests canonical graphDigests value
     * @param instanceId canonical instanceId value
     */
    public WorkflowWorkerCapabilities {
        java.util.Objects.requireNonNull(compilerDigest, "compilerDigest");
        java.util.Objects.requireNonNull(graphDigests, "graphDigests");
        graphDigests = graphDigests == null ? null : java.util.List.copyOf(graphDigests);
        java.util.Objects.requireNonNull(instanceId, "instanceId");
    }
}
