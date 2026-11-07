// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Organization fleet public key delivered only by the fixed certificate-authenticated Control origin.
 *
 * @param kid canonical kid value
 * @param n canonical n value
 * @param e canonical e value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record KillTrustKey(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "kid", required = true) String kid,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "n", required = true) String n,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "e", required = true) String e
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param kid canonical kid value
     * @param n canonical n value
     * @param e canonical e value
     */
    public KillTrustKey {
        java.util.Objects.requireNonNull(kid, "kid");
        java.util.Objects.requireNonNull(n, "n");
        java.util.Objects.requireNonNull(e, "e");
        if (e != null && !java.util.Objects.equals(e, "AQAB")) throw new IllegalArgumentException("e must be AQAB");
    }
}
