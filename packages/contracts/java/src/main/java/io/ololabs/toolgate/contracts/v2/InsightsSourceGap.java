// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Admission counts that may be missing: a skipped sequence, or an epoch that stopped without a final batch.
 *
 * @param tenantId canonical tenantId value
 * @param gatewayId canonical gatewayId value
 * @param epoch canonical epoch value
 * @param lastSequence canonical lastSequence value
 * @param missingSequences canonical missingSequences value
 * @param lastSeenUnixMs canonical lastSeenUnixMs value
 * @param droppedRequests canonical droppedRequests value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record InsightsSourceGap(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tenantId", required = true) String tenantId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "gatewayId", required = true) String gatewayId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "epoch", required = true) String epoch,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "lastSequence", required = true) Long lastSequence,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "missingSequences", required = true) Long missingSequences,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "lastSeenUnixMs", required = true) Long lastSeenUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "droppedRequests", required = true) Long droppedRequests
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param tenantId canonical tenantId value
     * @param gatewayId canonical gatewayId value
     * @param epoch canonical epoch value
     * @param lastSequence canonical lastSequence value
     * @param missingSequences canonical missingSequences value
     * @param lastSeenUnixMs canonical lastSeenUnixMs value
     * @param droppedRequests canonical droppedRequests value
     */
    public InsightsSourceGap {
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        java.util.Objects.requireNonNull(gatewayId, "gatewayId");
        java.util.Objects.requireNonNull(epoch, "epoch");
        java.util.Objects.requireNonNull(lastSequence, "lastSequence");
        java.util.Objects.requireNonNull(missingSequences, "missingSequences");
        java.util.Objects.requireNonNull(lastSeenUnixMs, "lastSeenUnixMs");
        java.util.Objects.requireNonNull(droppedRequests, "droppedRequests");
    }
}
