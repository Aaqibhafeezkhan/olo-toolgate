// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** ADR 075 NativeFileScopeClosed; only protected qualified filesystem broker evidence.
 *
 * @param requestId canonical requestId value
 * @param leaseId canonical leaseId value
 * @param fencingToken canonical fencingToken value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record NativeFileScopeClosed(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "requestId", required = true) String requestId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "leaseId", required = true) String leaseId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "fencingToken", required = true) String fencingToken
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param requestId canonical requestId value
     * @param leaseId canonical leaseId value
     * @param fencingToken canonical fencingToken value
     */
    public NativeFileScopeClosed {
        java.util.Objects.requireNonNull(requestId, "requestId");
        java.util.Objects.requireNonNull(leaseId, "leaseId");
        java.util.Objects.requireNonNull(fencingToken, "fencingToken");
    }
}
