// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Review the exact retained proposal; reviewer identity is authenticated.
 *
 * @param expectedRevision canonical expectedRevision value
 * @param approve canonical approve value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record KillProposalDecision(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "expectedRevision", required = true) Long expectedRevision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "approve", required = true) Boolean approve
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param expectedRevision canonical expectedRevision value
     * @param approve canonical approve value
     */
    public KillProposalDecision {
        java.util.Objects.requireNonNull(expectedRevision, "expectedRevision");
        java.util.Objects.requireNonNull(approve, "approve");
    }
}
