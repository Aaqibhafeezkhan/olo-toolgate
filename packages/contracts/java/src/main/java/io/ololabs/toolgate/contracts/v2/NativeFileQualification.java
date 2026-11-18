// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** ADR 075 NativeFileQualification; only protected qualified filesystem broker evidence.
 *
 * @param deviceId canonical deviceId value
 * @param deviceGroupId canonical deviceGroupId value
 * @param imageDigest canonical imageDigest value
 * @param expiresAtUnixMs canonical expiresAtUnixMs value
 * @param acknowledgedRisk canonical acknowledgedRisk value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record NativeFileQualification(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deviceId", required = true) String deviceId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deviceGroupId", required = true) String deviceGroupId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "imageDigest", required = true) String imageDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "expiresAtUnixMs", required = true) Long expiresAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "acknowledgedRisk", required = true) String acknowledgedRisk
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param deviceId canonical deviceId value
     * @param deviceGroupId canonical deviceGroupId value
     * @param imageDigest canonical imageDigest value
     * @param expiresAtUnixMs canonical expiresAtUnixMs value
     * @param acknowledgedRisk canonical acknowledgedRisk value
     */
    public NativeFileQualification {
        java.util.Objects.requireNonNull(deviceId, "deviceId");
        java.util.Objects.requireNonNull(deviceGroupId, "deviceGroupId");
        java.util.Objects.requireNonNull(imageDigest, "imageDigest");
        java.util.Objects.requireNonNull(expiresAtUnixMs, "expiresAtUnixMs");
        java.util.Objects.requireNonNull(acknowledgedRisk, "acknowledgedRisk");
        if (acknowledgedRisk != null && !java.util.Objects.equals(acknowledgedRisk, "UNFENCED_FILESYSTEM")) throw new IllegalArgumentException("acknowledgedRisk must be UNFENCED_FILESYSTEM");
    }
}
