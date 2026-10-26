// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Aggregates for a range. Data before unavailableBeforeUnixMs has expired at this granularity and is absent, not zero.
 *
 * @param granularity canonical granularity value
 * @param fromUnixMs canonical fromUnixMs value
 * @param toUnixMs canonical toUnixMs value
 * @param groupBy canonical groupBy value
 * @param unavailableBeforeUnixMs canonical unavailableBeforeUnixMs value
 * @param histogramBoundsMs canonical histogramBoundsMs value
 * @param points canonical points value
 * @param admissions canonical admissions value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record InsightsSeries(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "granularity", required = true) InsightsGranularity granularity,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "fromUnixMs", required = true) Long fromUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toUnixMs", required = true) Long toUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "groupBy", required = true) String groupBy,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "unavailableBeforeUnixMs", required = false) Long unavailableBeforeUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "histogramBoundsMs", required = true) java.util.List<Long> histogramBoundsMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "points", required = true) java.util.List<InsightsSeriesPoint> points,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "admissions", required = true) java.util.List<InsightsAdmissionPoint> admissions
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param granularity canonical granularity value
     * @param fromUnixMs canonical fromUnixMs value
     * @param toUnixMs canonical toUnixMs value
     * @param groupBy canonical groupBy value
     * @param unavailableBeforeUnixMs canonical unavailableBeforeUnixMs value
     * @param histogramBoundsMs canonical histogramBoundsMs value
     * @param points canonical points value
     * @param admissions canonical admissions value
     */
    public InsightsSeries {
        java.util.Objects.requireNonNull(granularity, "granularity");
        java.util.Objects.requireNonNull(fromUnixMs, "fromUnixMs");
        java.util.Objects.requireNonNull(toUnixMs, "toUnixMs");
        java.util.Objects.requireNonNull(groupBy, "groupBy");
        java.util.Objects.requireNonNull(histogramBoundsMs, "histogramBoundsMs");
        histogramBoundsMs = histogramBoundsMs == null ? null : java.util.List.copyOf(histogramBoundsMs);
        java.util.Objects.requireNonNull(points, "points");
        points = points == null ? null : java.util.List.copyOf(points);
        java.util.Objects.requireNonNull(admissions, "admissions");
        admissions = admissions == null ? null : java.util.List.copyOf(admissions);
    }
}
