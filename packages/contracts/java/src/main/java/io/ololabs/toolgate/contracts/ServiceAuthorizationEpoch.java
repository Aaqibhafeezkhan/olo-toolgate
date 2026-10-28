// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Coalesced tenant revocation feed snapshot. Services fail closed if polling becomes stale.
 *
 * @param tenantId canonical tenantId value
 * @param authorizationEpoch canonical authorizationEpoch value
 * @param observedAtUnixMs canonical observedAtUnixMs value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ServiceAuthorizationEpoch(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tenantId", required = true) String tenantId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "authorizationEpoch", required = true) Long authorizationEpoch,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "observedAtUnixMs", required = true) Long observedAtUnixMs
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param tenantId canonical tenantId value
     * @param authorizationEpoch canonical authorizationEpoch value
     * @param observedAtUnixMs canonical observedAtUnixMs value
     */
    public ServiceAuthorizationEpoch {
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        java.util.Objects.requireNonNull(authorizationEpoch, "authorizationEpoch");
        java.util.Objects.requireNonNull(observedAtUnixMs, "observedAtUnixMs");
    }
}
