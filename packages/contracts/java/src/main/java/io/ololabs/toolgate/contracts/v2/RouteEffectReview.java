// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Review stamp created by Control, never accepted from package or worker input.
 *
 * @param policy canonical policy value
 * @param reviewedBy canonical reviewedBy value
 * @param reviewedAtUnixMs canonical reviewedAtUnixMs value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record RouteEffectReview(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "policy", required = true) RouteEffectPolicy policy,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "reviewedBy", required = true) String reviewedBy,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "reviewedAtUnixMs", required = true) Long reviewedAtUnixMs
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param policy canonical policy value
     * @param reviewedBy canonical reviewedBy value
     * @param reviewedAtUnixMs canonical reviewedAtUnixMs value
     */
    public RouteEffectReview {
        java.util.Objects.requireNonNull(policy, "policy");
        java.util.Objects.requireNonNull(reviewedBy, "reviewedBy");
        java.util.Objects.requireNonNull(reviewedAtUnixMs, "reviewedAtUnixMs");
    }
}
