// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** One authenticated independent review of retained kill or recovery intent.
 *
 * @param reviewerUserId canonical reviewerUserId value
 * @param approved canonical approved value
 * @param reviewedAtUnixMs canonical reviewedAtUnixMs value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record KillReview(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "reviewerUserId", required = true) String reviewerUserId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "approved", required = true) Boolean approved,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "reviewedAtUnixMs", required = true) Long reviewedAtUnixMs
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param reviewerUserId canonical reviewerUserId value
     * @param approved canonical approved value
     * @param reviewedAtUnixMs canonical reviewedAtUnixMs value
     */
    public KillReview {
        java.util.Objects.requireNonNull(reviewerUserId, "reviewerUserId");
        java.util.Objects.requireNonNull(approved, "approved");
        java.util.Objects.requireNonNull(reviewedAtUnixMs, "reviewedAtUnixMs");
    }
}
