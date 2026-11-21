// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Signed webhook delivery envelope. Use subscriptionId to select the signing key, verify timestamp and raw-body signature, then deduplicate by event.eventId.
 *
 * @param subscriptionId canonical subscriptionId value
 * @param event canonical event value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ApplicationWebhookDelivery(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "subscriptionId", required = true) String subscriptionId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "event", required = true) ApplicationEvent event
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param subscriptionId canonical subscriptionId value
     * @param event canonical event value
     */
    public ApplicationWebhookDelivery {
        java.util.Objects.requireNonNull(subscriptionId, "subscriptionId");
        java.util.Objects.requireNonNull(event, "event");
    }
}
