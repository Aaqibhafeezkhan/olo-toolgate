// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Short-lived signed user/tenant access token. Obtain a new token through password login after expiry.
 *
 * @param accessToken canonical accessToken value
 * @param tokenType canonical tokenType value
 * @param expiresIn canonical expiresIn value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ApplicationAccessToken(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "accessToken", required = true) String accessToken,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tokenType", required = true) String tokenType,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "expiresIn", required = true) Long expiresIn
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param accessToken canonical accessToken value
     * @param tokenType canonical tokenType value
     * @param expiresIn canonical expiresIn value
     */
    public ApplicationAccessToken {
        java.util.Objects.requireNonNull(accessToken, "accessToken");
        java.util.Objects.requireNonNull(tokenType, "tokenType");
        java.util.Objects.requireNonNull(expiresIn, "expiresIn");
    }
}
