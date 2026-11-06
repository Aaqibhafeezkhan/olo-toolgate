// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Immutable independently reviewed downstream evidence. Never creates a new permit.
 *
 * @param expectedRevision canonical expectedRevision value
 * @param state canonical state value
 * @param completedResources canonical completedResources value
 * @param evidenceDigest canonical evidenceDigest value
 * @param resultDigest canonical resultDigest value
 * @param attemptSettlement canonical attemptSettlement value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record EnterpriseReconciliationRequest(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "expectedRevision", required = true) Long expectedRevision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "state", required = true) EnterpriseReconciledState state,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "completedResources", required = true) java.util.List<ResourceDescriptor> completedResources,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "evidenceDigest", required = true) String evidenceDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "resultDigest", required = false) String resultDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "attemptSettlement", required = false) EnterpriseAttemptSettlementReview attemptSettlement
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param expectedRevision canonical expectedRevision value
     * @param state canonical state value
     * @param completedResources canonical completedResources value
     * @param evidenceDigest canonical evidenceDigest value
     * @param resultDigest canonical resultDigest value
     * @param attemptSettlement canonical attemptSettlement value
     */
    public EnterpriseReconciliationRequest {
        java.util.Objects.requireNonNull(expectedRevision, "expectedRevision");
        java.util.Objects.requireNonNull(state, "state");
        java.util.Objects.requireNonNull(completedResources, "completedResources");
        completedResources = completedResources == null ? null : java.util.List.copyOf(completedResources);
        java.util.Objects.requireNonNull(evidenceDigest, "evidenceDigest");
    }
}
