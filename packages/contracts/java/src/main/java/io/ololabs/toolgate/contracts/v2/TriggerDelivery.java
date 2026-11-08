// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** One delivery of a routed event to one consumer.
 *
 * @param deliveryId canonical deliveryId value
 * @param consumer canonical consumer value
 * @param cursor canonical cursor value
 * @param eventSource canonical eventSource value
 * @param eventId canonical eventId value
 * @param eventType canonical eventType value
 * @param assignment canonical assignment value
 * @param state canonical state value
 * @param attempts canonical attempts value
 * @param createdAtUnixMs canonical createdAtUnixMs value
 * @param updatedAtUnixMs canonical updatedAtUnixMs value
 * @param nextAttemptAtUnixMs canonical nextAttemptAtUnixMs value
 * @param reason canonical reason value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerDelivery(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deliveryId", required = true) String deliveryId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "consumer", required = true) TriggerDeliveryConsumer consumer,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "cursor", required = true) String cursor,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "eventSource", required = true) String eventSource,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "eventId", required = true) String eventId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "eventType", required = true) String eventType,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "assignment", required = true) String assignment,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "state", required = true) TriggerDeliveryState state,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "attempts", required = true) Long attempts,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "createdAtUnixMs", required = true) Long createdAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "updatedAtUnixMs", required = true) Long updatedAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "nextAttemptAtUnixMs", required = false) Long nextAttemptAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "reason", required = false) String reason
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param deliveryId canonical deliveryId value
     * @param consumer canonical consumer value
     * @param cursor canonical cursor value
     * @param eventSource canonical eventSource value
     * @param eventId canonical eventId value
     * @param eventType canonical eventType value
     * @param assignment canonical assignment value
     * @param state canonical state value
     * @param attempts canonical attempts value
     * @param createdAtUnixMs canonical createdAtUnixMs value
     * @param updatedAtUnixMs canonical updatedAtUnixMs value
     * @param nextAttemptAtUnixMs canonical nextAttemptAtUnixMs value
     * @param reason canonical reason value
     */
    public TriggerDelivery {
        java.util.Objects.requireNonNull(deliveryId, "deliveryId");
        java.util.Objects.requireNonNull(consumer, "consumer");
        java.util.Objects.requireNonNull(cursor, "cursor");
        java.util.Objects.requireNonNull(eventSource, "eventSource");
        java.util.Objects.requireNonNull(eventId, "eventId");
        java.util.Objects.requireNonNull(eventType, "eventType");
        java.util.Objects.requireNonNull(assignment, "assignment");
        java.util.Objects.requireNonNull(state, "state");
        java.util.Objects.requireNonNull(attempts, "attempts");
        java.util.Objects.requireNonNull(createdAtUnixMs, "createdAtUnixMs");
        java.util.Objects.requireNonNull(updatedAtUnixMs, "updatedAtUnixMs");
    }
}
