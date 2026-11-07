// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Read-only instance resource (plan 9.2).
 *
 * @param instanceId canonical instanceId value
 * @param assignment canonical assignment value
 * @param triggerId canonical triggerId value
 * @param executor canonical executor value
 * @param executorId canonical executorId value
 * @param packageDigest canonical packageDigest value
 * @param triggerDescriptorDigest canonical triggerDescriptorDigest value
 * @param configRevision canonical configRevision value
 * @param desiredState canonical desiredState value
 * @param desiredStateSource canonical desiredStateSource value
 * @param phase canonical phase value
 * @param conditions canonical conditions value
 * @param leaseEpoch canonical leaseEpoch value
 * @param lastSeenAtUnixMs canonical lastSeenAtUnixMs value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerInstanceView(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "instanceId", required = true) String instanceId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "assignment", required = true) String assignment,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "triggerId", required = true) String triggerId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "executor", required = true) TriggerExecutor executor,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "executorId", required = true) String executorId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageDigest", required = true) String packageDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "triggerDescriptorDigest", required = true) String triggerDescriptorDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "configRevision", required = true) Long configRevision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "desiredState", required = true) TriggerDesiredState desiredState,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "desiredStateSource", required = true) String desiredStateSource,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "phase", required = true) TriggerPhase phase,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "conditions", required = true) java.util.List<TriggerCondition> conditions,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "leaseEpoch", required = false) Long leaseEpoch,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "lastSeenAtUnixMs", required = false) Long lastSeenAtUnixMs
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param instanceId canonical instanceId value
     * @param assignment canonical assignment value
     * @param triggerId canonical triggerId value
     * @param executor canonical executor value
     * @param executorId canonical executorId value
     * @param packageDigest canonical packageDigest value
     * @param triggerDescriptorDigest canonical triggerDescriptorDigest value
     * @param configRevision canonical configRevision value
     * @param desiredState canonical desiredState value
     * @param desiredStateSource canonical desiredStateSource value
     * @param phase canonical phase value
     * @param conditions canonical conditions value
     * @param leaseEpoch canonical leaseEpoch value
     * @param lastSeenAtUnixMs canonical lastSeenAtUnixMs value
     */
    public TriggerInstanceView {
        java.util.Objects.requireNonNull(instanceId, "instanceId");
        java.util.Objects.requireNonNull(assignment, "assignment");
        java.util.Objects.requireNonNull(triggerId, "triggerId");
        java.util.Objects.requireNonNull(executor, "executor");
        java.util.Objects.requireNonNull(executorId, "executorId");
        java.util.Objects.requireNonNull(packageDigest, "packageDigest");
        java.util.Objects.requireNonNull(triggerDescriptorDigest, "triggerDescriptorDigest");
        java.util.Objects.requireNonNull(configRevision, "configRevision");
        java.util.Objects.requireNonNull(desiredState, "desiredState");
        java.util.Objects.requireNonNull(desiredStateSource, "desiredStateSource");
        java.util.Objects.requireNonNull(phase, "phase");
        java.util.Objects.requireNonNull(conditions, "conditions");
        conditions = conditions == null ? null : java.util.List.copyOf(conditions);
    }
}
