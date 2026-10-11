// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Successful consumption.
 *
 * @param invocationId canonical invocationId value
 * @param fencingToken canonical fencingToken value
 * @param leaseExpiresAtUnixMs canonical leaseExpiresAtUnixMs value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record PermitConsumed(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "invocationId", required = true) String invocationId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "fencingToken", required = true) String fencingToken,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "leaseExpiresAtUnixMs", required = true) Long leaseExpiresAtUnixMs
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param invocationId canonical invocationId value
     * @param fencingToken canonical fencingToken value
     * @param leaseExpiresAtUnixMs canonical leaseExpiresAtUnixMs value
     */
    public PermitConsumed {
        java.util.Objects.requireNonNull(invocationId, "invocationId");
        java.util.Objects.requireNonNull(fencingToken, "fencingToken");
        java.util.Objects.requireNonNull(leaseExpiresAtUnixMs, "leaseExpiresAtUnixMs");
    }
}
