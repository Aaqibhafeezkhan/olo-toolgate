// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Durable exact binding and outcome state. OUTCOME_UNKNOWN never authorizes a blind retry.
 *
 * @param id canonical id value
 * @param requestDigest canonical requestDigest value
 * @param evaluation canonical evaluation value
 * @param state canonical state value
 * @param revision canonical revision value
 * @param authorizationEpoch canonical authorizationEpoch value
 * @param reservedNonce canonical reservedNonce value
 * @param expiresAtUnixMs canonical expiresAtUnixMs value
 * @param completedResources canonical completedResources value
 * @param downstreamIdempotencyKey canonical downstreamIdempotencyKey value
 * @param resultDigest canonical resultDigest value
 * @param diagnosticId canonical diagnosticId value
 * @param reconciliation canonical reconciliation value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record EnterpriseInvocation(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "id", required = true) String id,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "requestDigest", required = true) String requestDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "evaluation", required = true) EnterpriseEvaluation evaluation,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "state", required = true) EnterpriseInvocationState state,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "revision", required = true) Long revision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "authorizationEpoch", required = true) Long authorizationEpoch,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "reservedNonce", required = false) String reservedNonce,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "expiresAtUnixMs", required = true) Long expiresAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "completedResources", required = true) java.util.List<ResourceDescriptor> completedResources,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "downstreamIdempotencyKey", required = false) String downstreamIdempotencyKey,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "resultDigest", required = false) String resultDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "diagnosticId", required = true) String diagnosticId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "reconciliation", required = false) ReconciliationMethod reconciliation
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param id canonical id value
     * @param requestDigest canonical requestDigest value
     * @param evaluation canonical evaluation value
     * @param state canonical state value
     * @param revision canonical revision value
     * @param authorizationEpoch canonical authorizationEpoch value
     * @param reservedNonce canonical reservedNonce value
     * @param expiresAtUnixMs canonical expiresAtUnixMs value
     * @param completedResources canonical completedResources value
     * @param downstreamIdempotencyKey canonical downstreamIdempotencyKey value
     * @param resultDigest canonical resultDigest value
     * @param diagnosticId canonical diagnosticId value
     * @param reconciliation canonical reconciliation value
     */
    public EnterpriseInvocation {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(requestDigest, "requestDigest");
        java.util.Objects.requireNonNull(evaluation, "evaluation");
        java.util.Objects.requireNonNull(state, "state");
        java.util.Objects.requireNonNull(revision, "revision");
        java.util.Objects.requireNonNull(authorizationEpoch, "authorizationEpoch");
        java.util.Objects.requireNonNull(expiresAtUnixMs, "expiresAtUnixMs");
        java.util.Objects.requireNonNull(completedResources, "completedResources");
        completedResources = completedResources == null ? null : java.util.List.copyOf(completedResources);
        java.util.Objects.requireNonNull(diagnosticId, "diagnosticId");
    }
}
