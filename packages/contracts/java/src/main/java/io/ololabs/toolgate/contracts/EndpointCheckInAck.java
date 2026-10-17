// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Endpoint identity foundation wire model.
 *
 * @param deviceId canonical deviceId value
 * @param sequence canonical sequence value
 * @param serverTimeUnixMs canonical serverTimeUnixMs value
 * @param nextIntervalMs canonical nextIntervalMs value
 * @param identity canonical identity value
 * @param adoption canonical adoption value
 * @param task canonical task value
 * @param serverName canonical serverName value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record EndpointCheckInAck(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deviceId", required = true) String deviceId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "sequence", required = true) Long sequence,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "serverTimeUnixMs", required = true) Long serverTimeUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "nextIntervalMs", required = true) Long nextIntervalMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "identity", required = false) DeviceIdentity identity,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "adoption", required = false) EndpointAdoption adoption,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "task", required = false) RemoteToolTask task,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "serverName", required = false) String serverName
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param deviceId canonical deviceId value
     * @param sequence canonical sequence value
     * @param serverTimeUnixMs canonical serverTimeUnixMs value
     * @param nextIntervalMs canonical nextIntervalMs value
     * @param identity canonical identity value
     * @param adoption canonical adoption value
     * @param task canonical task value
     * @param serverName canonical serverName value
     */
    public EndpointCheckInAck {
        java.util.Objects.requireNonNull(deviceId, "deviceId");
        java.util.Objects.requireNonNull(sequence, "sequence");
        java.util.Objects.requireNonNull(serverTimeUnixMs, "serverTimeUnixMs");
        java.util.Objects.requireNonNull(nextIntervalMs, "nextIntervalMs");
    }
}
