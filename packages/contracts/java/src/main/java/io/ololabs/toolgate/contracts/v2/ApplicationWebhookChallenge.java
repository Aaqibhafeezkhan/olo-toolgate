// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Signed destination-ownership challenge. Return HMAC-SHA256(secret, verify.subscriptionId.challenge) using the supplied subscription ID and challenge as proof.
 *
 * @param type canonical type value
 * @param subscriptionId canonical subscriptionId value
 * @param challenge canonical challenge value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ApplicationWebhookChallenge(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "type", required = true) String type,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "subscriptionId", required = true) String subscriptionId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "challenge", required = true) String challenge
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param type canonical type value
     * @param subscriptionId canonical subscriptionId value
     * @param challenge canonical challenge value
     */
    public ApplicationWebhookChallenge {
        java.util.Objects.requireNonNull(type, "type");
        java.util.Objects.requireNonNull(subscriptionId, "subscriptionId");
        java.util.Objects.requireNonNull(challenge, "challenge");
    }
}
