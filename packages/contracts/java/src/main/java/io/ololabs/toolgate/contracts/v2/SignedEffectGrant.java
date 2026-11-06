// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** JWS with the fixed toolgate-effect-grant-v1 type; not interchangeable with admission or service permits.
 *
 * @param jws canonical jws value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record SignedEffectGrant(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "jws", required = true) String jws
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param jws canonical jws value
     */
    public SignedEffectGrant {
        java.util.Objects.requireNonNull(jws, "jws");
    }
}
