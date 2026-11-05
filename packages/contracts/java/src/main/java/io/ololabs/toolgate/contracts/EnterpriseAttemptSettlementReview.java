// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Exact closed-attempt scope attestation for independent maker/checker review; never execution authority.
 *
 * @param expectedOutcomeRevision canonical expectedOutcomeRevision value
 * @param attemptIds canonical attemptIds value
 * @param noFurtherEffects canonical noFurtherEffects value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record EnterpriseAttemptSettlementReview(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "expectedOutcomeRevision", required = true) Long expectedOutcomeRevision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "attemptIds", required = true) java.util.List<String> attemptIds,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "noFurtherEffects", required = true) Boolean noFurtherEffects
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param expectedOutcomeRevision canonical expectedOutcomeRevision value
     * @param attemptIds canonical attemptIds value
     * @param noFurtherEffects canonical noFurtherEffects value
     */
    public EnterpriseAttemptSettlementReview {
        java.util.Objects.requireNonNull(expectedOutcomeRevision, "expectedOutcomeRevision");
        java.util.Objects.requireNonNull(attemptIds, "attemptIds");
        attemptIds = attemptIds == null ? null : java.util.List.copyOf(attemptIds);
        java.util.Objects.requireNonNull(noFurtherEffects, "noFurtherEffects");
    }
}
