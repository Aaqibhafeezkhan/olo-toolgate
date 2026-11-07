// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** A single-use WebSocket ticket (plan 13.2), valid for 30 s, sent as the subprotocol `ticket.` followed by the ticket value.
 *
 * @param ticket canonical ticket value
 * @param expiresAtUnixMs canonical expiresAtUnixMs value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerStreamTicket(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "ticket", required = true) String ticket,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "expiresAtUnixMs", required = true) Long expiresAtUnixMs
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param ticket canonical ticket value
     * @param expiresAtUnixMs canonical expiresAtUnixMs value
     */
    public TriggerStreamTicket {
        java.util.Objects.requireNonNull(ticket, "ticket");
        java.util.Objects.requireNonNull(expiresAtUnixMs, "expiresAtUnixMs");
    }
}
