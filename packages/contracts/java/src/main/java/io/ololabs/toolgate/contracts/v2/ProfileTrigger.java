// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** A client trigger assignment the profile declares for one of its package's trigger exports (ADR 058). It targets the entry's deviceGroupIds; a profile that declares any trigger enables triggers for the deployment.
 *
 * @param assignment canonical assignment value
 * @param triggerId canonical triggerId value
 * @param config canonical config value
 * @param desiredState canonical desiredState value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ProfileTrigger(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "assignment", required = true) String assignment,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "triggerId", required = true) String triggerId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "config", required = true) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> config,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "desiredState", required = false) TriggerDesiredState desiredState
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param assignment canonical assignment value
     * @param triggerId canonical triggerId value
     * @param config canonical config value
     * @param desiredState canonical desiredState value
     */
    public ProfileTrigger {
        java.util.Objects.requireNonNull(assignment, "assignment");
        java.util.Objects.requireNonNull(triggerId, "triggerId");
        java.util.Objects.requireNonNull(config, "config");
        config = config == null ? null : java.util.Map.copyOf(config);
    }
}
