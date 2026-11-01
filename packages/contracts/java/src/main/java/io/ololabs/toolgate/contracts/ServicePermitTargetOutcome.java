// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Authenticated target evidence, accepted after grant expiry without renewing authority.
 *
 * @param claim canonical claim value
 * @param outcome canonical outcome value
 * @param resultDigest canonical resultDigest value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ServicePermitTargetOutcome(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "claim", required = true) ServicePermitClaimReference claim,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "outcome", required = true) ServicePermitTargetOutcomeKind outcome,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "resultDigest", required = true) String resultDigest
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param claim canonical claim value
     * @param outcome canonical outcome value
     * @param resultDigest canonical resultDigest value
     */
    public ServicePermitTargetOutcome {
        java.util.Objects.requireNonNull(claim, "claim");
        java.util.Objects.requireNonNull(outcome, "outcome");
        java.util.Objects.requireNonNull(resultDigest, "resultDigest");
    }
}
