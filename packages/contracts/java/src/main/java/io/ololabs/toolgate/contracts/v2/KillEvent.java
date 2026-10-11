// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Immutable kill event.
 *
 * @param eventId canonical eventId value
 * @param issuer canonical issuer value
 * @param scope canonical scope value
 * @param mode canonical mode value
 * @param reason canonical reason value
 * @param issuedAtUnixMs canonical issuedAtUnixMs value
 * @param signature canonical signature value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record KillEvent(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "eventId", required = true) String eventId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "issuer", required = true) KillIssuer issuer,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "scope", required = true) KillScope scope,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "mode", required = true) KillMode mode,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "reason", required = true) String reason,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "issuedAtUnixMs", required = true) Long issuedAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "signature", required = true) String signature
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param eventId canonical eventId value
     * @param issuer canonical issuer value
     * @param scope canonical scope value
     * @param mode canonical mode value
     * @param reason canonical reason value
     * @param issuedAtUnixMs canonical issuedAtUnixMs value
     * @param signature canonical signature value
     */
    public KillEvent {
        java.util.Objects.requireNonNull(eventId, "eventId");
        java.util.Objects.requireNonNull(issuer, "issuer");
        java.util.Objects.requireNonNull(scope, "scope");
        java.util.Objects.requireNonNull(mode, "mode");
        java.util.Objects.requireNonNull(reason, "reason");
        java.util.Objects.requireNonNull(issuedAtUnixMs, "issuedAtUnixMs");
        java.util.Objects.requireNonNull(signature, "signature");
    }
}
