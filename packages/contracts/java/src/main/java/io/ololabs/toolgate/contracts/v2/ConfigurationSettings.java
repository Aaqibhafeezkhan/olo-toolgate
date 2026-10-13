// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Configuration review settings. requiredReviews 0 applies changes immediately.
 *
 * @param requiredReviews canonical requiredReviews value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ConfigurationSettings(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "requiredReviews", required = true) Long requiredReviews
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param requiredReviews canonical requiredReviews value
     */
    public ConfigurationSettings {
        java.util.Objects.requireNonNull(requiredReviews, "requiredReviews");
    }
}
