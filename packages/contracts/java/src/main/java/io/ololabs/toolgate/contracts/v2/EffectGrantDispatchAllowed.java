// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Only explicit true permits the single transport send.
 *
 * @param authorized canonical authorized value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record EffectGrantDispatchAllowed(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "authorized", required = true) Boolean authorized
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param authorized canonical authorized value
     */
    public EffectGrantDispatchAllowed {
        java.util.Objects.requireNonNull(authorized, "authorized");
    }
}
