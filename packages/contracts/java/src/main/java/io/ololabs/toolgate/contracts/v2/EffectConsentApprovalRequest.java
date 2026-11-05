// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Authenticated explicit approval of one immutable preview and revision. No worker-provided replacement intent or validity window is accepted.
 *
 * @param previewDigest canonical previewDigest value
 * @param expectedRevision canonical expectedRevision value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record EffectConsentApprovalRequest(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "previewDigest", required = true) String previewDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "expectedRevision", required = true) Long expectedRevision
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param previewDigest canonical previewDigest value
     * @param expectedRevision canonical expectedRevision value
     */
    public EffectConsentApprovalRequest {
        java.util.Objects.requireNonNull(previewDigest, "previewDigest");
        java.util.Objects.requireNonNull(expectedRevision, "expectedRevision");
    }
}
