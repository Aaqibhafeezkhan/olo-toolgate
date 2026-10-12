// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Public deployment details attached only to an already visible enrolled device.
 *
 * @param deviceId canonical deviceId value
 * @param deployment canonical deployment value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record DeviceDeploymentRecord(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deviceId", required = true) String deviceId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deployment", required = true) DeviceDeploymentMetadata deployment
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param deviceId canonical deviceId value
     * @param deployment canonical deployment value
     */
    public DeviceDeploymentRecord {
        java.util.Objects.requireNonNull(deviceId, "deviceId");
        java.util.Objects.requireNonNull(deployment, "deployment");
    }
}
