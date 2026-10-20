// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Protected JWS header. Algorithm, message purpose and key selection are authenticated; no unprotected or remote-key headers.
 *
 * @param alg canonical alg value
 * @param typ canonical typ value
 * @param kid canonical kid value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record DiscoveryHeader(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "alg", required = true) String alg,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "typ", required = true) String typ,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "kid", required = true) String kid
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param alg canonical alg value
     * @param typ canonical typ value
     * @param kid canonical kid value
     */
    public DiscoveryHeader {
        java.util.Objects.requireNonNull(alg, "alg");
        if (alg != null && !java.util.Objects.equals(alg, "RS256")) throw new IllegalArgumentException("alg must be RS256");
        java.util.Objects.requireNonNull(typ, "typ");
        if (typ != null && !java.util.Objects.equals(typ, "toolgate-discovery+jws")) throw new IllegalArgumentException("typ must be toolgate-discovery+jws");
        java.util.Objects.requireNonNull(kid, "kid");
    }
}
