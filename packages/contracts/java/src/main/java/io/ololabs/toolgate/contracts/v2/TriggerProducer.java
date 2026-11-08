// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Producer identity Control attaches from the mTLS certificate, lease and signed package; never from the payload.
 *
 * @param kind canonical kind value
 * @param executorId canonical executorId value
 * @param deviceGroups canonical deviceGroups value
 * @param assignment canonical assignment value
 * @param instanceId canonical instanceId value
 * @param leaseEpoch canonical leaseEpoch value
 * @param packageDigest canonical packageDigest value
 * @param triggerDescriptorDigest canonical triggerDescriptorDigest value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerProducer(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "kind", required = true) TriggerProducerKind kind,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "executorId", required = false) String executorId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deviceGroups", required = false) java.util.List<String> deviceGroups,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "assignment", required = true) String assignment,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "instanceId", required = true) String instanceId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "leaseEpoch", required = false) Long leaseEpoch,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageDigest", required = false) String packageDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "triggerDescriptorDigest", required = false) String triggerDescriptorDigest
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param kind canonical kind value
     * @param executorId canonical executorId value
     * @param deviceGroups canonical deviceGroups value
     * @param assignment canonical assignment value
     * @param instanceId canonical instanceId value
     * @param leaseEpoch canonical leaseEpoch value
     * @param packageDigest canonical packageDigest value
     * @param triggerDescriptorDigest canonical triggerDescriptorDigest value
     */
    public TriggerProducer {
        java.util.Objects.requireNonNull(kind, "kind");
        deviceGroups = deviceGroups == null ? null : java.util.List.copyOf(deviceGroups);
        java.util.Objects.requireNonNull(assignment, "assignment");
        java.util.Objects.requireNonNull(instanceId, "instanceId");
    }
}
