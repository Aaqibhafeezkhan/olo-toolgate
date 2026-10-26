// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** A tenant's sessions with calls accepted in the range, most recent activity first. next continues the list.
 *
 * @param items canonical items value
 * @param next canonical next value
 * @param callsWithoutSession canonical callsWithoutSession value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record InsightsSessionPage(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "items", required = true) java.util.List<InsightsSession> items,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "next", required = false) String next,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "callsWithoutSession", required = true) Long callsWithoutSession
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param items canonical items value
     * @param next canonical next value
     * @param callsWithoutSession canonical callsWithoutSession value
     */
    public InsightsSessionPage {
        java.util.Objects.requireNonNull(items, "items");
        items = items == null ? null : java.util.List.copyOf(items);
        java.util.Objects.requireNonNull(callsWithoutSession, "callsWithoutSession");
    }
}
