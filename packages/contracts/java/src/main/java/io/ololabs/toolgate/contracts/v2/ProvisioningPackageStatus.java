// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** One pinned package: where provisioning stands and why it is not ready. hostIds and globalsDigest record the deployment it applied, so an unchanged repeat does not redeploy.
 *
 * @param packageId canonical packageId value
 * @param version canonical version value
 * @param packageDigest canonical packageDigest value
 * @param required canonical required value
 * @param state canonical state value
 * @param step canonical step value
 * @param reason canonical reason value
 * @param toolIds canonical toolIds value
 * @param hostIds canonical hostIds value
 * @param deviceGroupIds canonical deviceGroupIds value
 * @param deviceIds canonical deviceIds value
 * @param globalsDigest canonical globalsDigest value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ProvisioningPackageStatus(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageId", required = true) String packageId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "version", required = true) String version,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageDigest", required = false) String packageDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "required", required = true) Boolean required,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "state", required = true) ProvisioningState state,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "step", required = true) ProvisioningStep step,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "reason", required = false) String reason,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toolIds", required = false) java.util.List<String> toolIds,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "hostIds", required = false) java.util.List<String> hostIds,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deviceGroupIds", required = false) java.util.List<String> deviceGroupIds,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deviceIds", required = false) java.util.List<String> deviceIds,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "globalsDigest", required = false) String globalsDigest
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param packageId canonical packageId value
     * @param version canonical version value
     * @param packageDigest canonical packageDigest value
     * @param required canonical required value
     * @param state canonical state value
     * @param step canonical step value
     * @param reason canonical reason value
     * @param toolIds canonical toolIds value
     * @param hostIds canonical hostIds value
     * @param deviceGroupIds canonical deviceGroupIds value
     * @param deviceIds canonical deviceIds value
     * @param globalsDigest canonical globalsDigest value
     */
    public ProvisioningPackageStatus {
        java.util.Objects.requireNonNull(packageId, "packageId");
        java.util.Objects.requireNonNull(version, "version");
        java.util.Objects.requireNonNull(required, "required");
        java.util.Objects.requireNonNull(state, "state");
        java.util.Objects.requireNonNull(step, "step");
        toolIds = toolIds == null ? null : java.util.List.copyOf(toolIds);
        hostIds = hostIds == null ? null : java.util.List.copyOf(hostIds);
        deviceGroupIds = deviceGroupIds == null ? null : java.util.List.copyOf(deviceGroupIds);
        deviceIds = deviceIds == null ? null : java.util.List.copyOf(deviceIds);
    }
}
