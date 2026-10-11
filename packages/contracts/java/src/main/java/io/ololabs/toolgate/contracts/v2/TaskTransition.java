// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** One recorded transition.
 *
 * @param from canonical from value
 * @param to canonical to value
 * @param actor canonical actor value
 * @param atUnixMs canonical atUnixMs value
 * @param reason canonical reason value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TaskTransition(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "from", required = true) TaskState from,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "to", required = true) TaskState to,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "actor", required = true) TransitionActor actor,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "atUnixMs", required = true) Long atUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "reason", required = false) String reason
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param from canonical from value
     * @param to canonical to value
     * @param actor canonical actor value
     * @param atUnixMs canonical atUnixMs value
     * @param reason canonical reason value
     */
    public TaskTransition {
        java.util.Objects.requireNonNull(from, "from");
        java.util.Objects.requireNonNull(to, "to");
        java.util.Objects.requireNonNull(actor, "actor");
        java.util.Objects.requireNonNull(atUnixMs, "atUnixMs");
    }
}
