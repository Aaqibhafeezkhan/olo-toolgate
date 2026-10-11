// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Rollout plan attached at enable.
 *
 * @param strategy canonical strategy value
 * @param steps canonical steps value
 * @param autoRollback canonical autoRollback value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record RolloutPlan(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "strategy", required = true) RolloutStrategy strategy,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "steps", required = true) java.util.List<RolloutStep> steps,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "autoRollback", required = true) Boolean autoRollback
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param strategy canonical strategy value
     * @param steps canonical steps value
     * @param autoRollback canonical autoRollback value
     */
    public RolloutPlan {
        java.util.Objects.requireNonNull(strategy, "strategy");
        java.util.Objects.requireNonNull(steps, "steps");
        steps = steps == null ? null : java.util.List.copyOf(steps);
        java.util.Objects.requireNonNull(autoRollback, "autoRollback");
    }
}
