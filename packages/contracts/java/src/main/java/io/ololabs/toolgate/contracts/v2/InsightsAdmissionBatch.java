// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Gateway to Control: admission counts since the previous batch of this process epoch. Best-effort; Control ignores a repeated (gatewayId, epoch, sequence) and reports missing sequences as source gaps.
 *
 * @param gatewayId canonical gatewayId value
 * @param epoch canonical epoch value
 * @param sequence canonical sequence value
 * @param lastBatch canonical lastBatch value
 * @param droppedRequests canonical droppedRequests value
 * @param counts canonical counts value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record InsightsAdmissionBatch(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "gatewayId", required = true) String gatewayId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "epoch", required = true) String epoch,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "sequence", required = true) Long sequence,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "lastBatch", required = false) Boolean lastBatch,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "droppedRequests", required = false) Long droppedRequests,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "counts", required = true) java.util.List<InsightsAdmissionCount> counts
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param gatewayId canonical gatewayId value
     * @param epoch canonical epoch value
     * @param sequence canonical sequence value
     * @param lastBatch canonical lastBatch value
     * @param droppedRequests canonical droppedRequests value
     * @param counts canonical counts value
     */
    public InsightsAdmissionBatch {
        java.util.Objects.requireNonNull(gatewayId, "gatewayId");
        java.util.Objects.requireNonNull(epoch, "epoch");
        java.util.Objects.requireNonNull(sequence, "sequence");
        java.util.Objects.requireNonNull(counts, "counts");
        counts = counts == null ? null : java.util.List.copyOf(counts);
    }
}
