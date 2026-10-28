// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Admission decisions in one bucket.
 *
 * @param bucketStartUnixMs canonical bucketStartUnixMs value
 * @param category canonical category value
 * @param count canonical count value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record InsightsAdmissionPoint(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "bucketStartUnixMs", required = true) Long bucketStartUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "category", required = true) InsightsAdmissionCategory category,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "count", required = true) Long count
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param bucketStartUnixMs canonical bucketStartUnixMs value
     * @param category canonical category value
     * @param count canonical count value
     */
    public InsightsAdmissionPoint {
        java.util.Objects.requireNonNull(bucketStartUnixMs, "bucketStartUnixMs");
        java.util.Objects.requireNonNull(category, "category");
        java.util.Objects.requireNonNull(count, "count");
    }
}
