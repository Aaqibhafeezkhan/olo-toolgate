// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** This owner won the single send transition; a lost response cannot be replayed into new authority.
 *
 * @param authorized canonical authorized value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ServicePermitDispatchAllowed(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "authorized", required = true) Boolean authorized
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param authorized canonical authorized value
     */
    public ServicePermitDispatchAllowed {
        java.util.Objects.requireNonNull(authorized, "authorized");
    }
}
