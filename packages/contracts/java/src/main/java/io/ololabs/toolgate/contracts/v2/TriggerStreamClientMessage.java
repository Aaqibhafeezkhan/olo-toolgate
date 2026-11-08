// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** A client message on `toolgate.triggers.v1`. subscribe: subscription, optional resumeAfter. watch: sources, optional filter. ack: subscription, cursor. credit: n. unsubscribe: subscription.
 *
 * @param type canonical type value
 * @param subscription canonical subscription value
 * @param resumeAfter canonical resumeAfter value
 * @param sources canonical sources value
 * @param filter canonical filter value
 * @param cursor canonical cursor value
 * @param n canonical n value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerStreamClientMessage(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "type", required = true) TriggerStreamClientType type,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "subscription", required = false) String subscription,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "resumeAfter", required = false) String resumeAfter,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "sources", required = false) TriggerSubscriptionSources sources,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "filter", required = false) String filter,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "cursor", required = false) String cursor,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "n", required = false) Long n
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param type canonical type value
     * @param subscription canonical subscription value
     * @param resumeAfter canonical resumeAfter value
     * @param sources canonical sources value
     * @param filter canonical filter value
     * @param cursor canonical cursor value
     * @param n canonical n value
     */
    public TriggerStreamClientMessage {
        java.util.Objects.requireNonNull(type, "type");
    }
}
