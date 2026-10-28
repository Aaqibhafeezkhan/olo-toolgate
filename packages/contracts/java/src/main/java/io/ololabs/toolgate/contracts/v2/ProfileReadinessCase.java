// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** A call the deployment makes through the Gateway, as the named agent, to declare the profile ready. Arguments are explicit; expected names fields the structured result must contain with these exact values.
 *
 * @param toolId canonical toolId value
 * @param agentId canonical agentId value
 * @param arguments canonical arguments value
 * @param expected canonical expected value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ProfileReadinessCase(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toolId", required = true) String toolId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "agentId", required = true) String agentId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "arguments", required = true) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> arguments,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "expected", required = false) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> expected
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param toolId canonical toolId value
     * @param agentId canonical agentId value
     * @param arguments canonical arguments value
     * @param expected canonical expected value
     */
    public ProfileReadinessCase {
        java.util.Objects.requireNonNull(toolId, "toolId");
        java.util.Objects.requireNonNull(agentId, "agentId");
        java.util.Objects.requireNonNull(arguments, "arguments");
        arguments = arguments == null ? null : java.util.Map.copyOf(arguments);
        expected = expected == null ? null : java.util.Map.copyOf(expected);
    }
}
