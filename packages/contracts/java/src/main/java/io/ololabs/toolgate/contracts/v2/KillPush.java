// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Push to Tool Hosts and clients for running work.
 *
 * @param tenantId canonical tenantId value
 * @param authorizationEpoch canonical authorizationEpoch value
 * @param kills canonical kills value
 * @param recoveries canonical recoveries value
 * @param signature canonical signature value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record KillPush(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tenantId", required = true) String tenantId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "authorizationEpoch", required = true) Long authorizationEpoch,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "kills", required = true) java.util.List<KillEvent> kills,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "recoveries", required = true) java.util.List<RecoveryEvent> recoveries,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "signature", required = true) String signature
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param tenantId canonical tenantId value
     * @param authorizationEpoch canonical authorizationEpoch value
     * @param kills canonical kills value
     * @param recoveries canonical recoveries value
     * @param signature canonical signature value
     */
    public KillPush {
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        java.util.Objects.requireNonNull(authorizationEpoch, "authorizationEpoch");
        java.util.Objects.requireNonNull(kills, "kills");
        kills = kills == null ? null : java.util.List.copyOf(kills);
        java.util.Objects.requireNonNull(recoveries, "recoveries");
        recoveries = recoveries == null ? null : java.util.List.copyOf(recoveries);
        java.util.Objects.requireNonNull(signature, "signature");
    }
}
