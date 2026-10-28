// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Public-only RSA JWK, restricted to 3072/4096-bit RS256 keys with exponent 65537. No private parameters or key URLs.
 *
 * @param kty canonical kty value
 * @param use canonical use value
 * @param alg canonical alg value
 * @param kid canonical kid value
 * @param n canonical n value
 * @param e canonical e value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record DiscoveryPublicKey(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "kty", required = true) String kty,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "use", required = true) String use,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "alg", required = true) String alg,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "kid", required = true) String kid,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "n", required = true) String n,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "e", required = true) String e
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param kty canonical kty value
     * @param use canonical use value
     * @param alg canonical alg value
     * @param kid canonical kid value
     * @param n canonical n value
     * @param e canonical e value
     */
    public DiscoveryPublicKey {
        java.util.Objects.requireNonNull(kty, "kty");
        if (kty != null && !java.util.Objects.equals(kty, "RSA")) throw new IllegalArgumentException("kty must be RSA");
        java.util.Objects.requireNonNull(use, "use");
        if (use != null && !java.util.Objects.equals(use, "sig")) throw new IllegalArgumentException("use must be sig");
        java.util.Objects.requireNonNull(alg, "alg");
        if (alg != null && !java.util.Objects.equals(alg, "RS256")) throw new IllegalArgumentException("alg must be RS256");
        java.util.Objects.requireNonNull(kid, "kid");
        java.util.Objects.requireNonNull(n, "n");
        java.util.Objects.requireNonNull(e, "e");
        if (e != null && !java.util.Objects.equals(e, "AQAB")) throw new IllegalArgumentException("e must be AQAB");
    }
}
