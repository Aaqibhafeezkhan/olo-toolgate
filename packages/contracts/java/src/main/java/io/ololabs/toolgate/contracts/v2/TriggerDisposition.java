// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Control-side terminal rejection of one record.
 *
 * @param instanceId canonical instanceId value
 * @param leaseEpoch canonical leaseEpoch value
 * @param outboxSeq canonical outboxSeq value
 * @param id canonical id value
 * @param disposition canonical disposition value
 * @param reason canonical reason value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerDisposition(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "instanceId", required = true) String instanceId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "leaseEpoch", required = true) Long leaseEpoch,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "outboxSeq", required = true) Long outboxSeq,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "id", required = true) String id,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "disposition", required = true) TriggerDispositionKind disposition,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "reason", required = true) String reason
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param instanceId canonical instanceId value
     * @param leaseEpoch canonical leaseEpoch value
     * @param outboxSeq canonical outboxSeq value
     * @param id canonical id value
     * @param disposition canonical disposition value
     * @param reason canonical reason value
     */
    public TriggerDisposition {
        java.util.Objects.requireNonNull(instanceId, "instanceId");
        java.util.Objects.requireNonNull(leaseEpoch, "leaseEpoch");
        java.util.Objects.requireNonNull(outboxSeq, "outboxSeq");
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(disposition, "disposition");
        java.util.Objects.requireNonNull(reason, "reason");
    }
}
