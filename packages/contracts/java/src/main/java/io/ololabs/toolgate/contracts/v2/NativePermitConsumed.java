// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Current native endpoint consumption: accepted invocation, live lease and frozen kill facts from one Control transaction. Not a Tool Host admission response.
 *
 * @param invocation canonical invocation value
 * @param fencingToken canonical fencingToken value
 * @param leaseExpiresAtUnixMs canonical leaseExpiresAtUnixMs value
 * @param authorizationEpoch canonical authorizationEpoch value
 * @param killScope canonical killScope value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record NativePermitConsumed(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "invocation", required = true) io.ololabs.toolgate.contracts.EnterpriseInvocation invocation,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "fencingToken", required = true) String fencingToken,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "leaseExpiresAtUnixMs", required = true) Long leaseExpiresAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "authorizationEpoch", required = true) Long authorizationEpoch,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "killScope", required = true) KillExecutionScope killScope
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param invocation canonical invocation value
     * @param fencingToken canonical fencingToken value
     * @param leaseExpiresAtUnixMs canonical leaseExpiresAtUnixMs value
     * @param authorizationEpoch canonical authorizationEpoch value
     * @param killScope canonical killScope value
     */
    public NativePermitConsumed {
        java.util.Objects.requireNonNull(invocation, "invocation");
        java.util.Objects.requireNonNull(fencingToken, "fencingToken");
        java.util.Objects.requireNonNull(leaseExpiresAtUnixMs, "leaseExpiresAtUnixMs");
        java.util.Objects.requireNonNull(authorizationEpoch, "authorizationEpoch");
        java.util.Objects.requireNonNull(killScope, "killScope");
    }
}
