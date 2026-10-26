// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** One bucket and outcome. histogram holds counts per InsightsSeries.histogramBoundsMs bucket (last is overflow).
 *
 * @param bucketStartUnixMs canonical bucketStartUnixMs value
 * @param key canonical key value
 * @param outcome canonical outcome value
 * @param count canonical count value
 * @param durationCount canonical durationCount value
 * @param durationSumMs canonical durationSumMs value
 * @param histogram canonical histogram value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record InsightsSeriesPoint(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "bucketStartUnixMs", required = true) Long bucketStartUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "key", required = true) String key,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "outcome", required = true) InsightsOutcome outcome,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "count", required = true) Long count,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "durationCount", required = true) Long durationCount,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "durationSumMs", required = true) Long durationSumMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "histogram", required = true) java.util.List<Long> histogram
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param bucketStartUnixMs canonical bucketStartUnixMs value
     * @param key canonical key value
     * @param outcome canonical outcome value
     * @param count canonical count value
     * @param durationCount canonical durationCount value
     * @param durationSumMs canonical durationSumMs value
     * @param histogram canonical histogram value
     */
    public InsightsSeriesPoint {
        java.util.Objects.requireNonNull(bucketStartUnixMs, "bucketStartUnixMs");
        java.util.Objects.requireNonNull(key, "key");
        java.util.Objects.requireNonNull(outcome, "outcome");
        java.util.Objects.requireNonNull(count, "count");
        java.util.Objects.requireNonNull(durationCount, "durationCount");
        java.util.Objects.requireNonNull(durationSumMs, "durationSumMs");
        java.util.Objects.requireNonNull(histogram, "histogram");
        histogram = histogram == null ? null : java.util.List.copyOf(histogram);
    }
}
