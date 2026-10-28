// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** RFC 7515 compact RS256 JWS with a protected toolgate-discovery+jws header and TenantDiscovery v2 payload. No v1 or alternate-algorithm fallback.
 *
 * @param jws canonical jws value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record SignedTenantDiscovery(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "jws", required = true) String jws
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param jws canonical jws value
     */
    public SignedTenantDiscovery {
        java.util.Objects.requireNonNull(jws, "jws");
    }
}
