// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Scoped exact-effect review presentation, with a revision that changes on approval or revocation.
 *
 * @param preview canonical preview value
 * @param previewDigest canonical previewDigest value
 * @param state canonical state value
 * @param revision canonical revision value
 * @param reviewedBy canonical reviewedBy value
 * @param approvedAtUnixMs canonical approvedAtUnixMs value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record EffectConsentView(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "preview", required = true) EffectConsentPreview preview,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "previewDigest", required = true) String previewDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "state", required = true) EffectConsentState state,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "revision", required = true) Long revision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "reviewedBy", required = false) String reviewedBy,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "approvedAtUnixMs", required = false) Long approvedAtUnixMs
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param preview canonical preview value
     * @param previewDigest canonical previewDigest value
     * @param state canonical state value
     * @param revision canonical revision value
     * @param reviewedBy canonical reviewedBy value
     * @param approvedAtUnixMs canonical approvedAtUnixMs value
     */
    public EffectConsentView {
        java.util.Objects.requireNonNull(preview, "preview");
        java.util.Objects.requireNonNull(previewDigest, "previewDigest");
        java.util.Objects.requireNonNull(state, "state");
        java.util.Objects.requireNonNull(revision, "revision");
    }
}
