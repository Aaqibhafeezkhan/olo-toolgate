// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Directory identity, enrolled approval and pending request with server-resolved target permissions.
 *
 * @param deviceId canonical deviceId value
 * @param systemExecutor canonical systemExecutor value
 * @param directoryDevice canonical directoryDevice value
 * @param endpointDevice canonical endpointDevice value
 * @param enrollment canonical enrollment value
 * @param registeredUser canonical registeredUser value
 * @param systemExecutorKind canonical systemExecutorKind value
 * @param systemAvailable canonical systemAvailable value
 * @param systemName canonical systemName value
 * @param ipAddress canonical ipAddress value
 * @param permissions canonical permissions value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record EndpointManagedDevice(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deviceId", required = true) String deviceId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "systemExecutor", required = true) Boolean systemExecutor,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "directoryDevice", required = false) ControlDevice directoryDevice,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "endpointDevice", required = false) EndpointDeviceRecord endpointDevice,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "enrollment", required = false) EndpointEnrollmentReview enrollment,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "registeredUser", required = false) ControlUser registeredUser,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "systemExecutorKind", required = false) SystemExecutorKind systemExecutorKind,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "systemAvailable", required = false) Boolean systemAvailable,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "systemName", required = false) String systemName,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "ipAddress", required = false) String ipAddress,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "permissions", required = false) java.util.List<String> permissions
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param deviceId canonical deviceId value
     * @param systemExecutor canonical systemExecutor value
     * @param directoryDevice canonical directoryDevice value
     * @param endpointDevice canonical endpointDevice value
     * @param enrollment canonical enrollment value
     * @param registeredUser canonical registeredUser value
     * @param systemExecutorKind canonical systemExecutorKind value
     * @param systemAvailable canonical systemAvailable value
     * @param systemName canonical systemName value
     * @param ipAddress canonical ipAddress value
     * @param permissions canonical permissions value
     */
    public EndpointManagedDevice {
        java.util.Objects.requireNonNull(deviceId, "deviceId");
        java.util.Objects.requireNonNull(systemExecutor, "systemExecutor");
        permissions = permissions == null ? null : java.util.List.copyOf(permissions);
    }
}
