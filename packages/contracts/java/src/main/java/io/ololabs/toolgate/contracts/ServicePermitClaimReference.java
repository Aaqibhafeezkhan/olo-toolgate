// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Recorded target claim reference; not an execution capability.
 *
 * @param authNonce canonical authNonce value
 * @param claimToken canonical claimToken value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ServicePermitClaimReference(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "authNonce", required = true) String authNonce,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "claimToken", required = true) String claimToken
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param authNonce canonical authNonce value
     * @param claimToken canonical claimToken value
     */
    public ServicePermitClaimReference {
        java.util.Objects.requireNonNull(authNonce, "authNonce");
        java.util.Objects.requireNonNull(claimToken, "claimToken");
    }
}
