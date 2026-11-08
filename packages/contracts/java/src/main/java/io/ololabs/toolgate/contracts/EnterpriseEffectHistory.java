// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** One immutable authorization-generation identity and its current evidence-derived state; response digests do not prove business-receipt equivalence.
 *
 * @param effectId canonical effectId value
 * @param generation canonical generation value
 * @param attemptId canonical attemptId value
 * @param routeId canonical routeId value
 * @param requestDigest canonical requestDigest value
 * @param state canonical state value
 * @param grade canonical grade value
 * @param fencing canonical fencing value
 * @param observations canonical observations value
 * @param evidenceCount canonical evidenceCount value
 * @param receiptDigest canonical receiptDigest value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record EnterpriseEffectHistory(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "effectId", required = true) String effectId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "generation", required = true) Long generation,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "attemptId", required = true) String attemptId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "routeId", required = true) String routeId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "requestDigest", required = true) String requestDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "state", required = true) EnterpriseEffectGenerationState state,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "grade", required = true) EnterpriseOutcomeGrade grade,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "fencing", required = true) EnterpriseEffectFencing fencing,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "observations", required = true) java.util.List<EnterpriseEvidenceObservation> observations,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "evidenceCount", required = true) Long evidenceCount,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "receiptDigest", required = false) String receiptDigest
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param effectId canonical effectId value
     * @param generation canonical generation value
     * @param attemptId canonical attemptId value
     * @param routeId canonical routeId value
     * @param requestDigest canonical requestDigest value
     * @param state canonical state value
     * @param grade canonical grade value
     * @param fencing canonical fencing value
     * @param observations canonical observations value
     * @param evidenceCount canonical evidenceCount value
     * @param receiptDigest canonical receiptDigest value
     */
    public EnterpriseEffectHistory {
        java.util.Objects.requireNonNull(effectId, "effectId");
        java.util.Objects.requireNonNull(generation, "generation");
        java.util.Objects.requireNonNull(attemptId, "attemptId");
        java.util.Objects.requireNonNull(routeId, "routeId");
        java.util.Objects.requireNonNull(requestDigest, "requestDigest");
        java.util.Objects.requireNonNull(state, "state");
        java.util.Objects.requireNonNull(grade, "grade");
        java.util.Objects.requireNonNull(fencing, "fencing");
        java.util.Objects.requireNonNull(observations, "observations");
        observations = observations == null ? null : java.util.List.copyOf(observations);
        java.util.Objects.requireNonNull(evidenceCount, "evidenceCount");
    }
}
