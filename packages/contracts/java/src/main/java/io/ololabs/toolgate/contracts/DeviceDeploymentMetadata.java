// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Operator-collected Docker deployment details for display only; never enrollment, permission or trusted network evidence.
 *
 * @param executionKind canonical executionKind value
 * @param containerId canonical containerId value
 * @param containerName canonical containerName value
 * @param systemName canonical systemName value
 * @param networkId canonical networkId value
 * @param networkName canonical networkName value
 * @param ipAddress canonical ipAddress value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record DeviceDeploymentMetadata(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "executionKind", required = true) DeviceExecutionKind executionKind,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "containerId", required = true) String containerId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "containerName", required = true) String containerName,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "systemName", required = true) String systemName,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "networkId", required = true) String networkId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "networkName", required = true) String networkName,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "ipAddress", required = true) String ipAddress
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param executionKind canonical executionKind value
     * @param containerId canonical containerId value
     * @param containerName canonical containerName value
     * @param systemName canonical systemName value
     * @param networkId canonical networkId value
     * @param networkName canonical networkName value
     * @param ipAddress canonical ipAddress value
     */
    public DeviceDeploymentMetadata {
        java.util.Objects.requireNonNull(executionKind, "executionKind");
        java.util.Objects.requireNonNull(containerId, "containerId");
        java.util.Objects.requireNonNull(containerName, "containerName");
        java.util.Objects.requireNonNull(systemName, "systemName");
        java.util.Objects.requireNonNull(networkId, "networkId");
        java.util.Objects.requireNonNull(networkName, "networkName");
        java.util.Objects.requireNonNull(ipAddress, "ipAddress");
    }
}
