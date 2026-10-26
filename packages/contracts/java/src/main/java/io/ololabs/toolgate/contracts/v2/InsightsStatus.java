// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Read first by the operator page: what is collected and how current it is.
 *
 * @param mode canonical mode value
 * @param generatedAtUnixMs canonical generatedAtUnixMs value
 * @param tenants canonical tenants value
 * @param unprojectedFacts canonical unprojectedFacts value
 * @param oldestUnprojectedUnixMs canonical oldestUnprojectedUnixMs value
 * @param summariesSuspendedTenants canonical summariesSuspendedTenants value
 * @param failedTenants canonical failedTenants value
 * @param sourceGaps canonical sourceGaps value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record InsightsStatus(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "mode", required = true) InsightsMode mode,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "generatedAtUnixMs", required = true) Long generatedAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tenants", required = true) Long tenants,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "unprojectedFacts", required = true) Long unprojectedFacts,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "oldestUnprojectedUnixMs", required = false) Long oldestUnprojectedUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "summariesSuspendedTenants", required = true) Long summariesSuspendedTenants,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "failedTenants", required = true) Long failedTenants,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "sourceGaps", required = true) java.util.List<InsightsSourceGap> sourceGaps
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param mode canonical mode value
     * @param generatedAtUnixMs canonical generatedAtUnixMs value
     * @param tenants canonical tenants value
     * @param unprojectedFacts canonical unprojectedFacts value
     * @param oldestUnprojectedUnixMs canonical oldestUnprojectedUnixMs value
     * @param summariesSuspendedTenants canonical summariesSuspendedTenants value
     * @param failedTenants canonical failedTenants value
     * @param sourceGaps canonical sourceGaps value
     */
    public InsightsStatus {
        java.util.Objects.requireNonNull(mode, "mode");
        java.util.Objects.requireNonNull(generatedAtUnixMs, "generatedAtUnixMs");
        java.util.Objects.requireNonNull(tenants, "tenants");
        java.util.Objects.requireNonNull(unprojectedFacts, "unprojectedFacts");
        java.util.Objects.requireNonNull(summariesSuspendedTenants, "summariesSuspendedTenants");
        java.util.Objects.requireNonNull(failedTenants, "failedTenants");
        java.util.Objects.requireNonNull(sourceGaps, "sourceGaps");
        sourceGaps = sourceGaps == null ? null : java.util.List.copyOf(sourceGaps);
    }
}
