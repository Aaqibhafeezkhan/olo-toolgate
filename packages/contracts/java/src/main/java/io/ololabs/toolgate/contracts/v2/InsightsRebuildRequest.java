// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Recompute one tenant's aggregate cells for a range from its summaries. dryRun only compares.
 *
 * @param tenantId canonical tenantId value
 * @param fromUnixMs canonical fromUnixMs value
 * @param toUnixMs canonical toUnixMs value
 * @param dryRun canonical dryRun value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record InsightsRebuildRequest(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tenantId", required = true) String tenantId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "fromUnixMs", required = true) Long fromUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toUnixMs", required = true) Long toUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "dryRun", required = true) Boolean dryRun
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param tenantId canonical tenantId value
     * @param fromUnixMs canonical fromUnixMs value
     * @param toUnixMs canonical toUnixMs value
     * @param dryRun canonical dryRun value
     */
    public InsightsRebuildRequest {
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        java.util.Objects.requireNonNull(fromUnixMs, "fromUnixMs");
        java.util.Objects.requireNonNull(toUnixMs, "toUnixMs");
        java.util.Objects.requireNonNull(dryRun, "dryRun");
    }
}
