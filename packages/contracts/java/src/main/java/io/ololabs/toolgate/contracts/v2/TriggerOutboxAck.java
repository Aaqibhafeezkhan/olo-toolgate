// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Highest contiguous resolved outboxSeq for one (instance, epoch).
 *
 * @param instanceId canonical instanceId value
 * @param leaseEpoch canonical leaseEpoch value
 * @param outboxSeq canonical outboxSeq value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerOutboxAck(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "instanceId", required = true) String instanceId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "leaseEpoch", required = true) Long leaseEpoch,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "outboxSeq", required = true) Long outboxSeq
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param instanceId canonical instanceId value
     * @param leaseEpoch canonical leaseEpoch value
     * @param outboxSeq canonical outboxSeq value
     */
    public TriggerOutboxAck {
        java.util.Objects.requireNonNull(instanceId, "instanceId");
        java.util.Objects.requireNonNull(leaseEpoch, "leaseEpoch");
        java.util.Objects.requireNonNull(outboxSeq, "outboxSeq");
    }
}
