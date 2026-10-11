// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Effective and superseded events.
 *
 * @param kills canonical kills value
 * @param recoveries canonical recoveries value
 * @param authorizationEpoch canonical authorizationEpoch value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record KillEventPage(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "kills", required = true) java.util.List<KillEvent> kills,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "recoveries", required = true) java.util.List<RecoveryEvent> recoveries,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "authorizationEpoch", required = true) Long authorizationEpoch
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param kills canonical kills value
     * @param recoveries canonical recoveries value
     * @param authorizationEpoch canonical authorizationEpoch value
     */
    public KillEventPage {
        java.util.Objects.requireNonNull(kills, "kills");
        kills = kills == null ? null : java.util.List.copyOf(kills);
        java.util.Objects.requireNonNull(recoveries, "recoveries");
        recoveries = recoveries == null ? null : java.util.List.copyOf(recoveries);
        java.util.Objects.requireNonNull(authorizationEpoch, "authorizationEpoch");
    }
}
