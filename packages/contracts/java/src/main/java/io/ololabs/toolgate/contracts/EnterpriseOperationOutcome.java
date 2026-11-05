// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Control-derived business outcome; metadata never grants execution authority or automatic retry.
 *
 * @param operationId canonical operationId value
 * @param state canonical state value
 * @param revision canonical revision value
 * @param grade canonical grade value
 * @param journalComplete canonical journalComplete value
 * @param attemptCount canonical attemptCount value
 * @param effectCount canonical effectCount value
 * @param unresolvedEffectCount canonical unresolvedEffectCount value
 * @param unsettledAttemptCount canonical unsettledAttemptCount value
 * @param finalized canonical finalized value
 * @param retryable canonical retryable value
 * @param deduplicationAssumed canonical deduplicationAssumed value
 * @param updatedAtUnixMs canonical updatedAtUnixMs value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record EnterpriseOperationOutcome(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "operationId", required = true) String operationId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "state", required = true) EnterpriseOperationState state,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "revision", required = true) Long revision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "grade", required = true) EnterpriseOutcomeGrade grade,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "journalComplete", required = true) Boolean journalComplete,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "attemptCount", required = true) Long attemptCount,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "effectCount", required = true) Long effectCount,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "unresolvedEffectCount", required = true) Long unresolvedEffectCount,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "unsettledAttemptCount", required = true) Long unsettledAttemptCount,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "finalized", required = true) Boolean finalized,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "retryable", required = true) Boolean retryable,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deduplicationAssumed", required = true) Boolean deduplicationAssumed,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "updatedAtUnixMs", required = true) Long updatedAtUnixMs
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param operationId canonical operationId value
     * @param state canonical state value
     * @param revision canonical revision value
     * @param grade canonical grade value
     * @param journalComplete canonical journalComplete value
     * @param attemptCount canonical attemptCount value
     * @param effectCount canonical effectCount value
     * @param unresolvedEffectCount canonical unresolvedEffectCount value
     * @param unsettledAttemptCount canonical unsettledAttemptCount value
     * @param finalized canonical finalized value
     * @param retryable canonical retryable value
     * @param deduplicationAssumed canonical deduplicationAssumed value
     * @param updatedAtUnixMs canonical updatedAtUnixMs value
     */
    public EnterpriseOperationOutcome {
        java.util.Objects.requireNonNull(operationId, "operationId");
        java.util.Objects.requireNonNull(state, "state");
        java.util.Objects.requireNonNull(revision, "revision");
        java.util.Objects.requireNonNull(grade, "grade");
        java.util.Objects.requireNonNull(journalComplete, "journalComplete");
        java.util.Objects.requireNonNull(attemptCount, "attemptCount");
        java.util.Objects.requireNonNull(effectCount, "effectCount");
        java.util.Objects.requireNonNull(unresolvedEffectCount, "unresolvedEffectCount");
        java.util.Objects.requireNonNull(unsettledAttemptCount, "unsettledAttemptCount");
        java.util.Objects.requireNonNull(finalized, "finalized");
        java.util.Objects.requireNonNull(retryable, "retryable");
        java.util.Objects.requireNonNull(deduplicationAssumed, "deduplicationAssumed");
        java.util.Objects.requireNonNull(updatedAtUnixMs, "updatedAtUnixMs");
    }
}
