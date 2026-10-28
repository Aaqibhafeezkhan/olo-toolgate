// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** How many cells the recomputation compared and how many differed from the live projection.
 *
 * @param tenantId canonical tenantId value
 * @param fromUnixMs canonical fromUnixMs value
 * @param toUnixMs canonical toUnixMs value
 * @param cellsCompared canonical cellsCompared value
 * @param cellsDiffering canonical cellsDiffering value
 * @param applied canonical applied value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record InsightsRebuildResult(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tenantId", required = true) String tenantId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "fromUnixMs", required = true) Long fromUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toUnixMs", required = true) Long toUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "cellsCompared", required = true) Long cellsCompared,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "cellsDiffering", required = true) Long cellsDiffering,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "applied", required = true) Boolean applied
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param tenantId canonical tenantId value
     * @param fromUnixMs canonical fromUnixMs value
     * @param toUnixMs canonical toUnixMs value
     * @param cellsCompared canonical cellsCompared value
     * @param cellsDiffering canonical cellsDiffering value
     * @param applied canonical applied value
     */
    public InsightsRebuildResult {
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        java.util.Objects.requireNonNull(fromUnixMs, "fromUnixMs");
        java.util.Objects.requireNonNull(toUnixMs, "toUnixMs");
        java.util.Objects.requireNonNull(cellsCompared, "cellsCompared");
        java.util.Objects.requireNonNull(cellsDiffering, "cellsDiffering");
        java.util.Objects.requireNonNull(applied, "applied");
    }
}
