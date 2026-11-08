// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** A server message on `toolgate.triggers.v1`. subscribed: subscription. event: subscription (absent for watch), cursor, event. gap: subscription, fromCursor, toCursor, reason RetentionExpired. revoked: subscription, reason. error: error.
 *
 * @param type canonical type value
 * @param subscription canonical subscription value
 * @param cursor canonical cursor value
 * @param event canonical event value
 * @param fromCursor canonical fromCursor value
 * @param toCursor canonical toCursor value
 * @param reason canonical reason value
 * @param error canonical error value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerStreamServerMessage(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "type", required = true) TriggerStreamServerType type,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "subscription", required = false) String subscription,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "cursor", required = false) String cursor,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "event", required = false) TriggerViewEvent event,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "fromCursor", required = false) String fromCursor,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toCursor", required = false) String toCursor,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "reason", required = false) String reason,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "error", required = false) TriggerStreamError error
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param type canonical type value
     * @param subscription canonical subscription value
     * @param cursor canonical cursor value
     * @param event canonical event value
     * @param fromCursor canonical fromCursor value
     * @param toCursor canonical toCursor value
     * @param reason canonical reason value
     * @param error canonical error value
     */
    public TriggerStreamServerMessage {
        java.util.Objects.requireNonNull(type, "type");
    }
}
