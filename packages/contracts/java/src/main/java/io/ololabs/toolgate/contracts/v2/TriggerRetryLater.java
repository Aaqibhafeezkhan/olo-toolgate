// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Retryable rejection; records from fromOutboxSeq are kept and re-sent.
 *
 * @param instanceId canonical instanceId value
 * @param leaseEpoch canonical leaseEpoch value
 * @param fromOutboxSeq canonical fromOutboxSeq value
 * @param retryAfterMs canonical retryAfterMs value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerRetryLater(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "instanceId", required = true) String instanceId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "leaseEpoch", required = true) Long leaseEpoch,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "fromOutboxSeq", required = true) Long fromOutboxSeq,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "retryAfterMs", required = true) Long retryAfterMs
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param instanceId canonical instanceId value
     * @param leaseEpoch canonical leaseEpoch value
     * @param fromOutboxSeq canonical fromOutboxSeq value
     * @param retryAfterMs canonical retryAfterMs value
     */
    public TriggerRetryLater {
        java.util.Objects.requireNonNull(instanceId, "instanceId");
        java.util.Objects.requireNonNull(leaseEpoch, "leaseEpoch");
        java.util.Objects.requireNonNull(fromOutboxSeq, "fromOutboxSeq");
        java.util.Objects.requireNonNull(retryAfterMs, "retryAfterMs");
    }
}
