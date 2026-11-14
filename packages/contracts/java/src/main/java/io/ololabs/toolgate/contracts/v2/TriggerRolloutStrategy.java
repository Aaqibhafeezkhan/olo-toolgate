// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Client package and configuration rollout strategy.
 *
 * @param rollingUpdate canonical rollingUpdate value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerRolloutStrategy(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "rollingUpdate", required = true) TriggerRollingUpdate rollingUpdate
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param rollingUpdate canonical rollingUpdate value
     */
    public TriggerRolloutStrategy {
        java.util.Objects.requireNonNull(rollingUpdate, "rollingUpdate");
    }
}
