// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Signed fleet lifecycle contract; organization deployment trust never grants runtime permission. trigger-desired is the desired trigger document (ADR 058), signed by the same organization key.
 *
 * @param alg canonical alg value
 * @param typ canonical typ value
 * @param kid canonical kid value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record FleetSignatureHeader(
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
    public FleetSignatureHeader {
        java.util.Objects.requireNonNull(alg, "alg");
        java.util.Objects.requireNonNull(typ, "typ");
        java.util.Objects.requireNonNull(kid, "kid");
    }
}
