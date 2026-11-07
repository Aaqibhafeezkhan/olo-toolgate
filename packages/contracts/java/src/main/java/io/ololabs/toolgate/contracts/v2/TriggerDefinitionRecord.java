// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** A trigger export of an uploaded package version (plan 10.2).
 *
 * @param triggerId canonical triggerId value
 * @param packageId canonical packageId value
 * @param packageVersion canonical packageVersion value
 * @param packageDigest canonical packageDigest value
 * @param triggerDescriptorDigest canonical triggerDescriptorDigest value
 * @param descriptor canonical descriptor value
 * @param securityDelta canonical securityDelta value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerDefinitionRecord(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "triggerId", required = true) String triggerId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageId", required = true) String packageId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageVersion", required = true) String packageVersion,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageDigest", required = true) String packageDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "triggerDescriptorDigest", required = true) String triggerDescriptorDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "descriptor", required = true) TriggerDescriptor descriptor,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "securityDelta", required = true) java.util.List<SecurityDeltaItem> securityDelta
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param triggerId canonical triggerId value
     * @param packageId canonical packageId value
     * @param packageVersion canonical packageVersion value
     * @param packageDigest canonical packageDigest value
     * @param triggerDescriptorDigest canonical triggerDescriptorDigest value
     * @param descriptor canonical descriptor value
     * @param securityDelta canonical securityDelta value
     */
    public TriggerDefinitionRecord {
        java.util.Objects.requireNonNull(triggerId, "triggerId");
        java.util.Objects.requireNonNull(packageId, "packageId");
        java.util.Objects.requireNonNull(packageVersion, "packageVersion");
        java.util.Objects.requireNonNull(packageDigest, "packageDigest");
        java.util.Objects.requireNonNull(triggerDescriptorDigest, "triggerDescriptorDigest");
        java.util.Objects.requireNonNull(descriptor, "descriptor");
        java.util.Objects.requireNonNull(securityDelta, "securityDelta");
        securityDelta = securityDelta == null ? null : java.util.List.copyOf(securityDelta);
    }
}
