// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Target-owned claim consumption. Replies are status only; local ownership controls execution.
 *
 * @param request canonical request value
 * @param claimToken canonical claimToken value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ServicePermitClaimConsumption(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "request", required = true) ServicePermitConsumption request,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "claimToken", required = true) String claimToken
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param request canonical request value
     * @param claimToken canonical claimToken value
     */
    public ServicePermitClaimConsumption {
        java.util.Objects.requireNonNull(request, "request");
        java.util.Objects.requireNonNull(claimToken, "claimToken");
    }
}
