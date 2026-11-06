// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Authenticated target consumption of an exact accepted request, after its durable local claim commits. Replays return status only.
 *
 * @param grant canonical grant value
 * @param claimToken canonical claimToken value
 * @param httpRequestDigest canonical httpRequestDigest value
 * @param executorKeySha256 canonical executorKeySha256 value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TargetClaimConsumption(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "grant", required = true) SignedEffectGrant grant,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "claimToken", required = true) String claimToken,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "httpRequestDigest", required = true) String httpRequestDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "executorKeySha256", required = true) String executorKeySha256
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param grant canonical grant value
     * @param claimToken canonical claimToken value
     * @param httpRequestDigest canonical httpRequestDigest value
     * @param executorKeySha256 canonical executorKeySha256 value
     */
    public TargetClaimConsumption {
        java.util.Objects.requireNonNull(grant, "grant");
        java.util.Objects.requireNonNull(claimToken, "claimToken");
        java.util.Objects.requireNonNull(httpRequestDigest, "httpRequestDigest");
        java.util.Objects.requireNonNull(executorKeySha256, "executorKeySha256");
    }
}
