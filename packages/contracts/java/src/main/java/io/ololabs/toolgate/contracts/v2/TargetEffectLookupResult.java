// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Scoped operator result for one read-only target lookup. Effect status does not imply whole-operation completion; no target key, receipt or credential is exposed.
 *
 * @param invocationId canonical invocationId value
 * @param effectId canonical effectId value
 * @param state canonical state value
 * @param outcomeRevision canonical outcomeRevision value
 * @param evidenceDigest canonical evidenceDigest value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TargetEffectLookupResult(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "invocationId", required = true) String invocationId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "effectId", required = true) String effectId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "state", required = true) TargetEffectStatus state,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "outcomeRevision", required = true) Long outcomeRevision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "evidenceDigest", required = false) String evidenceDigest
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param invocationId canonical invocationId value
     * @param effectId canonical effectId value
     * @param state canonical state value
     * @param outcomeRevision canonical outcomeRevision value
     * @param evidenceDigest canonical evidenceDigest value
     */
    public TargetEffectLookupResult {
        java.util.Objects.requireNonNull(invocationId, "invocationId");
        java.util.Objects.requireNonNull(effectId, "effectId");
        java.util.Objects.requireNonNull(state, "state");
        java.util.Objects.requireNonNull(outcomeRevision, "outcomeRevision");
    }
}
