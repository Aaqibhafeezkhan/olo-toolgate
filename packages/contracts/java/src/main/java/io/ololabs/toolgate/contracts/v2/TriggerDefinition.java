// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Trigger export (ADR 058, plan 5.9): the signed trigger descriptor plus packaging members outside the digest. Trigger names share the package export namespace with tools.
 *
 * @param descriptor canonical descriptor value
 * @param triggerDescriptorDigest canonical triggerDescriptorDigest value
 * @param runtimeId canonical runtimeId value
 * @param entryPoint canonical entryPoint value
 * @param artifactIds canonical artifactIds value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerDefinition(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "descriptor", required = true) TriggerDescriptor descriptor,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "triggerDescriptorDigest", required = true) String triggerDescriptorDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "runtimeId", required = true) String runtimeId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "entryPoint", required = true) String entryPoint,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "artifactIds", required = true) java.util.List<String> artifactIds
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param descriptor canonical descriptor value
     * @param triggerDescriptorDigest canonical triggerDescriptorDigest value
     * @param runtimeId canonical runtimeId value
     * @param entryPoint canonical entryPoint value
     * @param artifactIds canonical artifactIds value
     */
    public TriggerDefinition {
        java.util.Objects.requireNonNull(descriptor, "descriptor");
        java.util.Objects.requireNonNull(triggerDescriptorDigest, "triggerDescriptorDigest");
        java.util.Objects.requireNonNull(runtimeId, "runtimeId");
        java.util.Objects.requireNonNull(entryPoint, "entryPoint");
        java.util.Objects.requireNonNull(artifactIds, "artifactIds");
        artifactIds = artifactIds == null ? null : java.util.List.copyOf(artifactIds);
    }
}
