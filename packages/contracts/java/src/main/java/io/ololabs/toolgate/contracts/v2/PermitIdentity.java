// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Verified identity and epochs.
 *
 * @param tenantId canonical tenantId value
 * @param mode canonical mode value
 * @param agentId canonical agentId value
 * @param userId canonical userId value
 * @param chain canonical chain value
 * @param credentialEpoch canonical credentialEpoch value
 * @param authorizationEpoch canonical authorizationEpoch value
 * @param claims canonical claims value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record PermitIdentity(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tenantId", required = true) String tenantId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "mode", required = true) RequestMode mode,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "agentId", required = false) String agentId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "userId", required = false) String userId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "chain", required = true) java.util.List<ActorHop> chain,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "credentialEpoch", required = false) Long credentialEpoch,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "authorizationEpoch", required = true) Long authorizationEpoch,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "claims", required = false) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> claims
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param tenantId canonical tenantId value
     * @param mode canonical mode value
     * @param agentId canonical agentId value
     * @param userId canonical userId value
     * @param chain canonical chain value
     * @param credentialEpoch canonical credentialEpoch value
     * @param authorizationEpoch canonical authorizationEpoch value
     * @param claims canonical claims value
     */
    public PermitIdentity {
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        java.util.Objects.requireNonNull(mode, "mode");
        java.util.Objects.requireNonNull(chain, "chain");
        chain = chain == null ? null : java.util.List.copyOf(chain);
        java.util.Objects.requireNonNull(authorizationEpoch, "authorizationEpoch");
        claims = claims == null ? null : java.util.Map.copyOf(claims);
    }
}
