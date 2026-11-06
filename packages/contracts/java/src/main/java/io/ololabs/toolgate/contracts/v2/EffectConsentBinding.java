// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Signed admission reference to exact approved consent; never substitutes for current Control authority at the effect boundary.
 *
 * @param consentId canonical consentId value
 * @param previewDigest canonical previewDigest value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record EffectConsentBinding(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "consentId", required = true) String consentId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "previewDigest", required = true) String previewDigest
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param consentId canonical consentId value
     * @param previewDigest canonical previewDigest value
     */
    public EffectConsentBinding {
        java.util.Objects.requireNonNull(consentId, "consentId");
        java.util.Objects.requireNonNull(previewDigest, "previewDigest");
    }
}
