// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Requests of one category for one tool in one closed minute bucket.
 *
 * @param bucketStartUnixMs canonical bucketStartUnixMs value
 * @param toolId canonical toolId value
 * @param category canonical category value
 * @param count canonical count value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record InsightsAdmissionCount(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "bucketStartUnixMs", required = true) Long bucketStartUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toolId", required = false) String toolId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "category", required = true) InsightsAdmissionCategory category,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "count", required = true) Long count
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param bucketStartUnixMs canonical bucketStartUnixMs value
     * @param toolId canonical toolId value
     * @param category canonical category value
     * @param count canonical count value
     */
    public InsightsAdmissionCount {
        java.util.Objects.requireNonNull(bucketStartUnixMs, "bucketStartUnixMs");
        java.util.Objects.requireNonNull(category, "category");
        java.util.Objects.requireNonNull(count, "count");
    }
}
