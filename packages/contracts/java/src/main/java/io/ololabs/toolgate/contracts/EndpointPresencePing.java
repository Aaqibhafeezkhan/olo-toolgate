// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Key-proved device presence with the client's current local UTC offset for hourly revoked-device scheduling.
 *
 * @param announcement canonical announcement value
 * @param utcOffset canonical utcOffset value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record EndpointPresencePing(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "announcement", required = true) EndpointEnrollmentStart announcement,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "utcOffset", required = true) String utcOffset
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param announcement canonical announcement value
     * @param utcOffset canonical utcOffset value
     */
    public EndpointPresencePing {
        java.util.Objects.requireNonNull(announcement, "announcement");
        java.util.Objects.requireNonNull(utcOffset, "utcOffset");
    }
}
