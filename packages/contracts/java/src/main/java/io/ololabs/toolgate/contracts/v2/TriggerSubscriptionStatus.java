// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Subscription status.
 *
 * @param phase canonical phase value
 * @param reason canonical reason value
 * @param clearance canonical clearance value
 * @param ackedCursor canonical ackedCursor value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerSubscriptionStatus(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "phase", required = true) TriggerSubscriptionPhase phase,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "reason", required = false) String reason,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "clearance", required = true) TriggerClassification clearance,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "ackedCursor", required = false) String ackedCursor
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param phase canonical phase value
     * @param reason canonical reason value
     * @param clearance canonical clearance value
     * @param ackedCursor canonical ackedCursor value
     */
    public TriggerSubscriptionStatus {
        java.util.Objects.requireNonNull(phase, "phase");
        java.util.Objects.requireNonNull(clearance, "clearance");
    }
}
