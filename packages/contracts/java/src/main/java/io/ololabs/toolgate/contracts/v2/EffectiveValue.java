// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** One effective field and the layer it came from.
 *
 * @param field canonical field value
 * @param value canonical value value
 * @param layer canonical layer value
 * @param cappedBy canonical cappedBy value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record EffectiveValue(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "field", required = true) String field,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "value", required = true) com.fasterxml.jackson.databind.JsonNode value,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "layer", required = true) ConfigLayer layer,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "cappedBy", required = false) ConfigLayer cappedBy
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param field canonical field value
     * @param value canonical value value
     * @param layer canonical layer value
     * @param cappedBy canonical cappedBy value
     */
    public EffectiveValue {
        java.util.Objects.requireNonNull(field, "field");
        java.util.Objects.requireNonNull(value, "value");
        java.util.Objects.requireNonNull(layer, "layer");
    }
}
