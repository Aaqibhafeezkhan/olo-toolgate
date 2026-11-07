// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** One assignment label.
 *
 * @param key canonical key value
 * @param value canonical value value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerLabel(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "key", required = true) String key,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "value", required = true) String value
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param key canonical key value
     * @param value canonical value value
     */
    public TriggerLabel {
        java.util.Objects.requireNonNull(key, "key");
        java.util.Objects.requireNonNull(value, "value");
    }
}
