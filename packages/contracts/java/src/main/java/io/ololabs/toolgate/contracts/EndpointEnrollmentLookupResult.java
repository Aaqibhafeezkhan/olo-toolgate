// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** The pending enrollment a valid code names, for comparison with the client before approval.
 *
 * @param enrollmentId canonical enrollmentId value
 * @param deviceId canonical deviceId value
 * @param platform canonical platform value
 * @param keyFingerprint canonical keyFingerprint value
 * @param expiresAtUnixMs canonical expiresAtUnixMs value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record EndpointEnrollmentLookupResult(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "enrollmentId", required = true) String enrollmentId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deviceId", required = true) String deviceId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "platform", required = true) ClientPlatform platform,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "keyFingerprint", required = true) String keyFingerprint,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "expiresAtUnixMs", required = true) Long expiresAtUnixMs
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param enrollmentId canonical enrollmentId value
     * @param deviceId canonical deviceId value
     * @param platform canonical platform value
     * @param keyFingerprint canonical keyFingerprint value
     * @param expiresAtUnixMs canonical expiresAtUnixMs value
     */
    public EndpointEnrollmentLookupResult {
        java.util.Objects.requireNonNull(enrollmentId, "enrollmentId");
        java.util.Objects.requireNonNull(deviceId, "deviceId");
        java.util.Objects.requireNonNull(platform, "platform");
        java.util.Objects.requireNonNull(keyFingerprint, "keyFingerprint");
        java.util.Objects.requireNonNull(expiresAtUnixMs, "expiresAtUnixMs");
    }
}
