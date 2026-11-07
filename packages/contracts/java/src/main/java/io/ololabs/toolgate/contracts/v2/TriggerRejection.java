// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Drop the outbox entries of this lease epoch, stop the instance and resync. Records of other epochs are unaffected.
 *
 * @param instanceId canonical instanceId value
 * @param leaseEpoch canonical leaseEpoch value
 * @param reason canonical reason value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerRejection(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "instanceId", required = true) String instanceId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "leaseEpoch", required = true) Long leaseEpoch,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "reason", required = true) TriggerRejectionReason reason
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param instanceId canonical instanceId value
     * @param leaseEpoch canonical leaseEpoch value
     * @param reason canonical reason value
     */
    public TriggerRejection {
        java.util.Objects.requireNonNull(instanceId, "instanceId");
        java.util.Objects.requireNonNull(leaseEpoch, "leaseEpoch");
        java.util.Objects.requireNonNull(reason, "reason");
    }
}
