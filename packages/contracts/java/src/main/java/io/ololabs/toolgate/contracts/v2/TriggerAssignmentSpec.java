// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Desired assignment of one trigger definition (plan 9.1).
 *
 * @param trigger canonical trigger value
 * @param packageVersion canonical packageVersion value
 * @param target canonical target value
 * @param config canonical config value
 * @param globals canonical globals value
 * @param desiredState canonical desiredState value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerAssignmentSpec(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "trigger", required = true) String trigger,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageVersion", required = true) String packageVersion,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "target", required = true) TriggerTarget target,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "config", required = true) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> config,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "globals", required = false) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> globals,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "desiredState", required = true) TriggerDesiredState desiredState
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param trigger canonical trigger value
     * @param packageVersion canonical packageVersion value
     * @param target canonical target value
     * @param config canonical config value
     * @param globals canonical globals value
     * @param desiredState canonical desiredState value
     */
    public TriggerAssignmentSpec {
        java.util.Objects.requireNonNull(trigger, "trigger");
        java.util.Objects.requireNonNull(packageVersion, "packageVersion");
        java.util.Objects.requireNonNull(target, "target");
        java.util.Objects.requireNonNull(config, "config");
        config = config == null ? null : java.util.Map.copyOf(config);
        globals = globals == null ? null : java.util.Map.copyOf(globals);
        java.util.Objects.requireNonNull(desiredState, "desiredState");
    }
}
