// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** V1 external application ApplicationWebhookVerificationProof contract; current tenant and user authorization remain required.
 *
 * @param proof canonical proof value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ApplicationWebhookVerificationProof(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "proof", required = true) String proof
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param proof canonical proof value
     */
    public ApplicationWebhookVerificationProof {
        java.util.Objects.requireNonNull(proof, "proof");
    }
}
