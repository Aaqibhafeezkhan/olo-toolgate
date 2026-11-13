// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** A trigger export of a released package (ADR 058): how a device runs it. The device fetches the full descriptor by digest and checks it against this signed digest and the signed desired document.
 *
 * @param triggerId canonical triggerId value
 * @param packageDigest canonical packageDigest value
 * @param triggerDescriptorDigest canonical triggerDescriptorDigest value
 * @param runtimeId canonical runtimeId value
 * @param entryPoint canonical entryPoint value
 * @param fileAccess canonical fileAccess value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record FleetTriggerRegistration(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "triggerId", required = true) String triggerId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageDigest", required = true) String packageDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "triggerDescriptorDigest", required = true) String triggerDescriptorDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "runtimeId", required = true) String runtimeId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "entryPoint", required = true) String entryPoint,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "fileAccess", required = true) LocalFileAccess fileAccess
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param triggerId canonical triggerId value
     * @param packageDigest canonical packageDigest value
     * @param triggerDescriptorDigest canonical triggerDescriptorDigest value
     * @param runtimeId canonical runtimeId value
     * @param entryPoint canonical entryPoint value
     * @param fileAccess canonical fileAccess value
     */
    public FleetTriggerRegistration {
        java.util.Objects.requireNonNull(triggerId, "triggerId");
        java.util.Objects.requireNonNull(packageDigest, "packageDigest");
        java.util.Objects.requireNonNull(triggerDescriptorDigest, "triggerDescriptorDigest");
        java.util.Objects.requireNonNull(runtimeId, "runtimeId");
        java.util.Objects.requireNonNull(entryPoint, "entryPoint");
        java.util.Objects.requireNonNull(fileAccess, "fileAccess");
    }
}
