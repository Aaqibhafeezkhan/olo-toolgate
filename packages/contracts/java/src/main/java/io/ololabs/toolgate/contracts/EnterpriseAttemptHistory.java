// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Bounded attempt metadata; no bearer capabilities, raw input or target claim tokens.
 *
 * @param attemptId canonical attemptId value
 * @param ordinal canonical ordinal value
 * @param state canonical state value
 * @param consumed canonical consumed value
 * @param issuedAtUnixMs canonical issuedAtUnixMs value
 * @param expiresAtUnixMs canonical expiresAtUnixMs value
 * @param evidenceCount canonical evidenceCount value
 * @param scope canonical scope value
 * @param manifestSealed canonical manifestSealed value
 * @param manifestEffectCount canonical manifestEffectCount value
 * @param manifestDigest canonical manifestDigest value
 * @param settled canonical settled value
 * @param grade canonical grade value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record EnterpriseAttemptHistory(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "attemptId", required = true) String attemptId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "ordinal", required = true) Long ordinal,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "state", required = true) EnterpriseInvocationState state,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "consumed", required = true) Boolean consumed,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "issuedAtUnixMs", required = true) Long issuedAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "expiresAtUnixMs", required = true) Long expiresAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "evidenceCount", required = true) Long evidenceCount,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "scope", required = true) EnterpriseAttemptEffectScope scope,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "manifestSealed", required = true) Boolean manifestSealed,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "manifestEffectCount", required = true) Long manifestEffectCount,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "manifestDigest", required = false) String manifestDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "settled", required = true) Boolean settled,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "grade", required = true) EnterpriseOutcomeGrade grade
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param attemptId canonical attemptId value
     * @param ordinal canonical ordinal value
     * @param state canonical state value
     * @param consumed canonical consumed value
     * @param issuedAtUnixMs canonical issuedAtUnixMs value
     * @param expiresAtUnixMs canonical expiresAtUnixMs value
     * @param evidenceCount canonical evidenceCount value
     * @param scope canonical scope value
     * @param manifestSealed canonical manifestSealed value
     * @param manifestEffectCount canonical manifestEffectCount value
     * @param manifestDigest canonical manifestDigest value
     * @param settled canonical settled value
     * @param grade canonical grade value
     */
    public EnterpriseAttemptHistory {
        java.util.Objects.requireNonNull(attemptId, "attemptId");
        java.util.Objects.requireNonNull(ordinal, "ordinal");
        java.util.Objects.requireNonNull(state, "state");
        java.util.Objects.requireNonNull(consumed, "consumed");
        java.util.Objects.requireNonNull(issuedAtUnixMs, "issuedAtUnixMs");
        java.util.Objects.requireNonNull(expiresAtUnixMs, "expiresAtUnixMs");
        java.util.Objects.requireNonNull(evidenceCount, "evidenceCount");
        java.util.Objects.requireNonNull(scope, "scope");
        java.util.Objects.requireNonNull(manifestSealed, "manifestSealed");
        java.util.Objects.requireNonNull(manifestEffectCount, "manifestEffectCount");
        java.util.Objects.requireNonNull(settled, "settled");
        java.util.Objects.requireNonNull(grade, "grade");
    }
}
