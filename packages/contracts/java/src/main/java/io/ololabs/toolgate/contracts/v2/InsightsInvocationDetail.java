// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** The detail window: summary, attempts and, while facts are retained, the transitions.
 *
 * @param invocation canonical invocation value
 * @param attempts canonical attempts value
 * @param transitions canonical transitions value
 * @param transitionsRetained canonical transitionsRetained value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record InsightsInvocationDetail(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "invocation", required = true) InsightsInvocation invocation,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "attempts", required = true) java.util.List<InsightsAttempt> attempts,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "transitions", required = true) java.util.List<InsightsTransition> transitions,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "transitionsRetained", required = true) Boolean transitionsRetained
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param invocation canonical invocation value
     * @param attempts canonical attempts value
     * @param transitions canonical transitions value
     * @param transitionsRetained canonical transitionsRetained value
     */
    public InsightsInvocationDetail {
        java.util.Objects.requireNonNull(invocation, "invocation");
        java.util.Objects.requireNonNull(attempts, "attempts");
        attempts = attempts == null ? null : java.util.List.copyOf(attempts);
        java.util.Objects.requireNonNull(transitions, "transitions");
        transitions = transitions == null ? null : java.util.List.copyOf(transitions);
        java.util.Objects.requireNonNull(transitionsRetained, "transitionsRetained");
    }
}
