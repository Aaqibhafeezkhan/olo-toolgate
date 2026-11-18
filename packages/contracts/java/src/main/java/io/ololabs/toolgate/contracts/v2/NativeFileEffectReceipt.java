// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** ADR 075 NativeFileEffectReceipt; only protected qualified filesystem broker evidence.
 *
 * @param requestId canonical requestId value
 * @param leaseId canonical leaseId value
 * @param fencingToken canonical fencingToken value
 * @param effectId canonical effectId value
 * @param beforeSha256 canonical beforeSha256 value
 * @param afterSha256 canonical afterSha256 value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record NativeFileEffectReceipt(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "requestId", required = true) String requestId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "leaseId", required = true) String leaseId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "fencingToken", required = true) String fencingToken,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "effectId", required = true) String effectId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "beforeSha256", required = false) String beforeSha256,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "afterSha256", required = true) String afterSha256
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param requestId canonical requestId value
     * @param leaseId canonical leaseId value
     * @param fencingToken canonical fencingToken value
     * @param effectId canonical effectId value
     * @param beforeSha256 canonical beforeSha256 value
     * @param afterSha256 canonical afterSha256 value
     */
    public NativeFileEffectReceipt {
        java.util.Objects.requireNonNull(requestId, "requestId");
        java.util.Objects.requireNonNull(leaseId, "leaseId");
        java.util.Objects.requireNonNull(fencingToken, "fencingToken");
        java.util.Objects.requireNonNull(effectId, "effectId");
        java.util.Objects.requireNonNull(afterSha256, "afterSha256");
    }
}
