// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Declared identity claim.
 *
 * @param name canonical name value
 * @param claim canonical claim value
 * @param type canonical type value
 * @param required canonical required value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ClaimDeclaration(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "name", required = true) String name,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "claim", required = true) String claim,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "type", required = true) ValueType type,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "required", required = true) Boolean required
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param name canonical name value
     * @param claim canonical claim value
     * @param type canonical type value
     * @param required canonical required value
     */
    public ClaimDeclaration {
        java.util.Objects.requireNonNull(name, "name");
        java.util.Objects.requireNonNull(claim, "claim");
        java.util.Objects.requireNonNull(type, "type");
        java.util.Objects.requireNonNull(required, "required");
    }
}
