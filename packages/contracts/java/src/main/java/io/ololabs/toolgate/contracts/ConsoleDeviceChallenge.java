// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** A short-lived server challenge bound to a verified console session and device key.
 *
 * @param nonce canonical nonce value
 * @param deviceId canonical deviceId value
 * @param keyFingerprint canonical keyFingerprint value
 * @param expiresAtUnixMs canonical expiresAtUnixMs value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ConsoleDeviceChallenge(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "nonce", required = true) String nonce,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deviceId", required = true) String deviceId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "keyFingerprint", required = true) String keyFingerprint,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "expiresAtUnixMs", required = true) Long expiresAtUnixMs
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param nonce canonical nonce value
     * @param deviceId canonical deviceId value
     * @param keyFingerprint canonical keyFingerprint value
     * @param expiresAtUnixMs canonical expiresAtUnixMs value
     */
    public ConsoleDeviceChallenge {
        java.util.Objects.requireNonNull(nonce, "nonce");
        java.util.Objects.requireNonNull(deviceId, "deviceId");
        java.util.Objects.requireNonNull(keyFingerprint, "keyFingerprint");
        java.util.Objects.requireNonNull(expiresAtUnixMs, "expiresAtUnixMs");
    }
}
