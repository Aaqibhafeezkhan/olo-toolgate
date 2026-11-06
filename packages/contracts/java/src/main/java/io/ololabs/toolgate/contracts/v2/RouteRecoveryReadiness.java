// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Current reviewed contract facts for one write route; no operation-specific retry authority.
 *
 * @param routeId canonical routeId value
 * @param reason canonical reason value
 * @param lookupAvailable canonical lookupAvailable value
 * @param reviewExpiresAtUnixMs canonical reviewExpiresAtUnixMs value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record RouteRecoveryReadiness(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "routeId", required = true) String routeId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "reason", required = true) RecoveryReadinessReason reason,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "lookupAvailable", required = true) Boolean lookupAvailable,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "reviewExpiresAtUnixMs", required = false) Long reviewExpiresAtUnixMs
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param routeId canonical routeId value
     * @param reason canonical reason value
     * @param lookupAvailable canonical lookupAvailable value
     * @param reviewExpiresAtUnixMs canonical reviewExpiresAtUnixMs value
     */
    public RouteRecoveryReadiness {
        java.util.Objects.requireNonNull(routeId, "routeId");
        java.util.Objects.requireNonNull(reason, "reason");
        java.util.Objects.requireNonNull(lookupAvailable, "lookupAvailable");
    }
}
