// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** One resolved instance in the desired document (plan 8.1).
 *
 * @param instanceId canonical instanceId value
 * @param triggerId canonical triggerId value
 * @param packageDigest canonical packageDigest value
 * @param triggerDescriptorDigest canonical triggerDescriptorDigest value
 * @param configRevision canonical configRevision value
 * @param config canonical config value
 * @param bindingDigest canonical bindingDigest value
 * @param desiredState canonical desiredState value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerDesiredInstance(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "instanceId", required = true) String instanceId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "triggerId", required = true) String triggerId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageDigest", required = true) String packageDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "triggerDescriptorDigest", required = true) String triggerDescriptorDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "configRevision", required = true) Long configRevision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "config", required = true) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> config,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "bindingDigest", required = true) String bindingDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "desiredState", required = true) TriggerDesiredState desiredState
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param instanceId canonical instanceId value
     * @param triggerId canonical triggerId value
     * @param packageDigest canonical packageDigest value
     * @param triggerDescriptorDigest canonical triggerDescriptorDigest value
     * @param configRevision canonical configRevision value
     * @param config canonical config value
     * @param bindingDigest canonical bindingDigest value
     * @param desiredState canonical desiredState value
     */
    public TriggerDesiredInstance {
        java.util.Objects.requireNonNull(instanceId, "instanceId");
        java.util.Objects.requireNonNull(triggerId, "triggerId");
        java.util.Objects.requireNonNull(packageDigest, "packageDigest");
        java.util.Objects.requireNonNull(triggerDescriptorDigest, "triggerDescriptorDigest");
        java.util.Objects.requireNonNull(configRevision, "configRevision");
        java.util.Objects.requireNonNull(config, "config");
        config = config == null ? null : java.util.Map.copyOf(config);
        java.util.Objects.requireNonNull(bindingDigest, "bindingDigest");
        java.util.Objects.requireNonNull(desiredState, "desiredState");
    }
}
