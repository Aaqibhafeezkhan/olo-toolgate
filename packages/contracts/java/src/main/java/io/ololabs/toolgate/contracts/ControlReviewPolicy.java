// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Configuration review threshold in force for new changes.
 *
 * @param requiredReviews canonical requiredReviews value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ControlReviewPolicy(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "requiredReviews", required = true) Long requiredReviews
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param requiredReviews canonical requiredReviews value
     */
    public ControlReviewPolicy {
        java.util.Objects.requireNonNull(requiredReviews, "requiredReviews");
    }
}
