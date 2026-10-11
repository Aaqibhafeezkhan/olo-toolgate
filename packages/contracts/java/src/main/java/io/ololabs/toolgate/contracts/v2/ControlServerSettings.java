// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Settings record. revision is compared with If-Match.
 *
 * @param formatVersion canonical formatVersion value
 * @param revision canonical revision value
 * @param gateway canonical gateway value
 * @param deviceApproval canonical deviceApproval value
 * @param clientCheckIn canonical clientCheckIn value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ControlServerSettings(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "formatVersion", required = true) Long formatVersion,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "revision", required = true) Long revision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "gateway", required = true) GatewaySettings gateway,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deviceApproval", required = true) DeviceApproval deviceApproval,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "clientCheckIn", required = true) ClientCheckIn clientCheckIn
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param formatVersion canonical formatVersion value
     * @param revision canonical revision value
     * @param gateway canonical gateway value
     * @param deviceApproval canonical deviceApproval value
     * @param clientCheckIn canonical clientCheckIn value
     */
    public ControlServerSettings {
        java.util.Objects.requireNonNull(formatVersion, "formatVersion");
        if (formatVersion != null && !java.util.Objects.equals(formatVersion, 2L)) throw new IllegalArgumentException("formatVersion must be 2");
        java.util.Objects.requireNonNull(revision, "revision");
        java.util.Objects.requireNonNull(gateway, "gateway");
        java.util.Objects.requireNonNull(deviceApproval, "deviceApproval");
        java.util.Objects.requireNonNull(clientCheckIn, "clientCheckIn");
    }
}
