// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** One retained transition fact, for the stage timeline.
 *
 * @param factSeq canonical factSeq value
 * @param fromState canonical fromState value
 * @param toState canonical toState value
 * @param occurredAtUnixMs canonical occurredAtUnixMs value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record InsightsTransition(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "factSeq", required = true) Long factSeq,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "fromState", required = false) String fromState,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toState", required = true) String toState,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "occurredAtUnixMs", required = true) Long occurredAtUnixMs
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param factSeq canonical factSeq value
     * @param fromState canonical fromState value
     * @param toState canonical toState value
     * @param occurredAtUnixMs canonical occurredAtUnixMs value
     */
    public InsightsTransition {
        java.util.Objects.requireNonNull(factSeq, "factSeq");
        java.util.Objects.requireNonNull(toState, "toState");
        java.util.Objects.requireNonNull(occurredAtUnixMs, "occurredAtUnixMs");
    }
}
