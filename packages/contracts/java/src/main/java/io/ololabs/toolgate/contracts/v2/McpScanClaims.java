// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Claims of a scan permit (typ toolgate-mcp-scan-permit+jws), signed in the effect-signing domain. exp - iat is at most 10 s (admission); deadlineUnixMs bounds the whole scan. spkiPins is required for REFRESH.
 *
 * @param jti canonical jti value
 * @param iss canonical iss value
 * @param aud canonical aud value
 * @param iat canonical iat value
 * @param exp canonical exp value
 * @param scanId canonical scanId value
 * @param slug canonical slug value
 * @param registrationRevision canonical registrationRevision value
 * @param bindingId canonical bindingId value
 * @param purpose canonical purpose value
 * @param url canonical url value
 * @param auth canonical auth value
 * @param spkiPins canonical spkiPins value
 * @param approvalSequence canonical approvalSequence value
 * @param fencingToken canonical fencingToken value
 * @param deadlineUnixMs canonical deadlineUnixMs value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record McpScanClaims(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "jti", required = true) String jti,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "iss", required = true) String iss,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "aud", required = true) String aud,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "iat", required = true) Long iat,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "exp", required = true) Long exp,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "scanId", required = true) String scanId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "slug", required = true) String slug,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "registrationRevision", required = true) Long registrationRevision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "bindingId", required = true) String bindingId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "purpose", required = true) McpScanPurpose purpose,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "url", required = true) String url,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "auth", required = true) McpUpstreamAuth auth,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "spkiPins", required = true) java.util.List<String> spkiPins,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "approvalSequence", required = false) Long approvalSequence,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "fencingToken", required = true) String fencingToken,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deadlineUnixMs", required = true) Long deadlineUnixMs
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param jti canonical jti value
     * @param iss canonical iss value
     * @param aud canonical aud value
     * @param iat canonical iat value
     * @param exp canonical exp value
     * @param scanId canonical scanId value
     * @param slug canonical slug value
     * @param registrationRevision canonical registrationRevision value
     * @param bindingId canonical bindingId value
     * @param purpose canonical purpose value
     * @param url canonical url value
     * @param auth canonical auth value
     * @param spkiPins canonical spkiPins value
     * @param approvalSequence canonical approvalSequence value
     * @param fencingToken canonical fencingToken value
     * @param deadlineUnixMs canonical deadlineUnixMs value
     */
    public McpScanClaims {
        java.util.Objects.requireNonNull(jti, "jti");
        java.util.Objects.requireNonNull(iss, "iss");
        if (iss != null && !java.util.Objects.equals(iss, "control")) throw new IllegalArgumentException("iss must be control");
        java.util.Objects.requireNonNull(aud, "aud");
        java.util.Objects.requireNonNull(iat, "iat");
        java.util.Objects.requireNonNull(exp, "exp");
        java.util.Objects.requireNonNull(scanId, "scanId");
        java.util.Objects.requireNonNull(slug, "slug");
        java.util.Objects.requireNonNull(registrationRevision, "registrationRevision");
        java.util.Objects.requireNonNull(bindingId, "bindingId");
        java.util.Objects.requireNonNull(purpose, "purpose");
        java.util.Objects.requireNonNull(url, "url");
        java.util.Objects.requireNonNull(auth, "auth");
        java.util.Objects.requireNonNull(spkiPins, "spkiPins");
        spkiPins = spkiPins == null ? null : java.util.List.copyOf(spkiPins);
        java.util.Objects.requireNonNull(fencingToken, "fencingToken");
        java.util.Objects.requireNonNull(deadlineUnixMs, "deadlineUnixMs");
    }
}
