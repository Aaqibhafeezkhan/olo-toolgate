// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Direct HTTPS key bootstrap; never accept it from a worker or relayed push.
 *
 * @param tenantId canonical tenantId value
 * @param authorizationEpoch canonical authorizationEpoch value
 * @param keys canonical keys value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record KillTrustDocument(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tenantId", required = true) String tenantId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "authorizationEpoch", required = true) Long authorizationEpoch,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "keys", required = true) java.util.List<KillTrustKey> keys
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param tenantId canonical tenantId value
     * @param authorizationEpoch canonical authorizationEpoch value
     * @param keys canonical keys value
     */
    public KillTrustDocument {
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        java.util.Objects.requireNonNull(authorizationEpoch, "authorizationEpoch");
        java.util.Objects.requireNonNull(keys, "keys");
        keys = keys == null ? null : java.util.List.copyOf(keys);
    }
}
