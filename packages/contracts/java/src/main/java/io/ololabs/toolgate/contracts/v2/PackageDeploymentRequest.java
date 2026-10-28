// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Deploys an uploaded server package to the Tool Hosts with values for its declared globals. A destination's host is the global its hostSetting names.
 *
 * @param packageDigest canonical packageDigest value
 * @param globals canonical globals value
 * @param hostIds canonical hostIds value
 * @param deviceGroupIds canonical deviceGroupIds value
 * @param privateRanges canonical privateRanges value
 * @param destinationTls canonical destinationTls value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record PackageDeploymentRequest(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageDigest", required = true) String packageDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "globals", required = true) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> globals,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "hostIds", required = false) java.util.List<String> hostIds,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deviceGroupIds", required = false) java.util.List<String> deviceGroupIds,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "privateRanges", required = false) java.util.List<PrivateRangeApproval> privateRanges,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "destinationTls", required = false) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> destinationTls
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param packageDigest canonical packageDigest value
     * @param globals canonical globals value
     * @param hostIds canonical hostIds value
     * @param deviceGroupIds canonical deviceGroupIds value
     * @param privateRanges canonical privateRanges value
     * @param destinationTls canonical destinationTls value
     */
    public PackageDeploymentRequest {
        java.util.Objects.requireNonNull(packageDigest, "packageDigest");
        java.util.Objects.requireNonNull(globals, "globals");
        globals = globals == null ? null : java.util.Map.copyOf(globals);
        hostIds = hostIds == null ? null : java.util.List.copyOf(hostIds);
        deviceGroupIds = deviceGroupIds == null ? null : java.util.List.copyOf(deviceGroupIds);
        privateRanges = privateRanges == null ? null : java.util.List.copyOf(privateRanges);
        destinationTls = destinationTls == null ? null : java.util.Map.copyOf(destinationTls);
    }
}
