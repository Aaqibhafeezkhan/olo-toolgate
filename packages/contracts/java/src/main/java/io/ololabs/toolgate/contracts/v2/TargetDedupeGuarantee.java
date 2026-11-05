// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Explicit operator-reviewed target contract; its presence alone never authorizes resend.
 *
 * @param documentationUrl canonical documentationUrl value
 * @param retentionMs canonical retentionMs value
 * @param scope canonical scope value
 * @param scopeBindingDigest canonical scopeBindingDigest value
 * @param differentBody canonical differentBody value
 * @param reviewExpiresAtUnixMs canonical reviewExpiresAtUnixMs value
 * @param header canonical header value
 * @param credentialVersion canonical credentialVersion value
 * @param statusRouteId canonical statusRouteId value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TargetDedupeGuarantee(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "documentationUrl", required = true) String documentationUrl,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "retentionMs", required = true) Long retentionMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "scope", required = true) TargetDedupeScope scope,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "scopeBindingDigest", required = true) String scopeBindingDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "differentBody", required = true) TargetDedupeDifferentBody differentBody,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "reviewExpiresAtUnixMs", required = true) Long reviewExpiresAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "header", required = true) String header,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "credentialVersion", required = false) Long credentialVersion,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "statusRouteId", required = false) String statusRouteId
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param documentationUrl canonical documentationUrl value
     * @param retentionMs canonical retentionMs value
     * @param scope canonical scope value
     * @param scopeBindingDigest canonical scopeBindingDigest value
     * @param differentBody canonical differentBody value
     * @param reviewExpiresAtUnixMs canonical reviewExpiresAtUnixMs value
     * @param header canonical header value
     * @param credentialVersion canonical credentialVersion value
     * @param statusRouteId canonical statusRouteId value
     */
    public TargetDedupeGuarantee {
        java.util.Objects.requireNonNull(documentationUrl, "documentationUrl");
        java.util.Objects.requireNonNull(retentionMs, "retentionMs");
        java.util.Objects.requireNonNull(scope, "scope");
        java.util.Objects.requireNonNull(scopeBindingDigest, "scopeBindingDigest");
        java.util.Objects.requireNonNull(differentBody, "differentBody");
        java.util.Objects.requireNonNull(reviewExpiresAtUnixMs, "reviewExpiresAtUnixMs");
        java.util.Objects.requireNonNull(header, "header");
    }
}
