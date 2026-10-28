// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Quickstart demo account pool, read only at install. Never stored as directory records.
 *
 * @param prefix canonical prefix value
 * @param teamId canonical teamId value
 * @param agentGroupId canonical agentGroupId value
 * @param delegationId canonical delegationId value
 * @param claims canonical claims value
 * @param passClaims canonical passClaims value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ControlVisitorPool(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "prefix", required = true) String prefix,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "teamId", required = true) String teamId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "agentGroupId", required = true) String agentGroupId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "delegationId", required = false) String delegationId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "claims", required = false) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> claims,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "passClaims", required = false) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> passClaims
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param prefix canonical prefix value
     * @param teamId canonical teamId value
     * @param agentGroupId canonical agentGroupId value
     * @param delegationId canonical delegationId value
     * @param claims canonical claims value
     * @param passClaims canonical passClaims value
     */
    public ControlVisitorPool {
        java.util.Objects.requireNonNull(prefix, "prefix");
        java.util.Objects.requireNonNull(teamId, "teamId");
        java.util.Objects.requireNonNull(agentGroupId, "agentGroupId");
        claims = claims == null ? null : java.util.Map.copyOf(claims);
        passClaims = passClaims == null ? null : java.util.Map.copyOf(passClaims);
    }
}
