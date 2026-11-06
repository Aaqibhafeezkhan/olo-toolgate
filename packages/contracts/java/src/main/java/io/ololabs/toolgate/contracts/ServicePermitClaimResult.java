// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Status only. Never grants execution authority; the target must own its shared local claim.
 *
 * @param effectId canonical effectId value
 * @param authorizationGeneration canonical authorizationGeneration value
 * @param status canonical status value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ServicePermitClaimResult(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "effectId", required = true) String effectId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "authorizationGeneration", required = true) Long authorizationGeneration,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "status", required = true) ServicePermitClaimState status
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param effectId canonical effectId value
     * @param authorizationGeneration canonical authorizationGeneration value
     * @param status canonical status value
     */
    public ServicePermitClaimResult {
        java.util.Objects.requireNonNull(effectId, "effectId");
        java.util.Objects.requireNonNull(authorizationGeneration, "authorizationGeneration");
        java.util.Objects.requireNonNull(status, "status");
    }
}
