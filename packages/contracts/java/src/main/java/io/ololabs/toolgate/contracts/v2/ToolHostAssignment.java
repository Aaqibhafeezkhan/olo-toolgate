// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** One deployed tool a Tool Host runs. The host reports it in its check-in as a local tool, so Control places calls for it there.
 *
 * @param toolId canonical toolId value
 * @param action canonical action value
 * @param description canonical description value
 * @param enabled canonical enabled value
 * @param inputSchema canonical inputSchema value
 * @param toolDigest canonical toolDigest value
 * @param packageDigest canonical packageDigest value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ToolHostAssignment(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toolId", required = true) String toolId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "action", required = true) String action,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "description", required = true) String description,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "enabled", required = true) Boolean enabled,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "inputSchema", required = true) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> inputSchema,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toolDigest", required = true) String toolDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageDigest", required = true) String packageDigest
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param toolId canonical toolId value
     * @param action canonical action value
     * @param description canonical description value
     * @param enabled canonical enabled value
     * @param inputSchema canonical inputSchema value
     * @param toolDigest canonical toolDigest value
     * @param packageDigest canonical packageDigest value
     */
    public ToolHostAssignment {
        java.util.Objects.requireNonNull(toolId, "toolId");
        java.util.Objects.requireNonNull(action, "action");
        java.util.Objects.requireNonNull(description, "description");
        java.util.Objects.requireNonNull(enabled, "enabled");
        java.util.Objects.requireNonNull(inputSchema, "inputSchema");
        inputSchema = inputSchema == null ? null : java.util.Map.copyOf(inputSchema);
        java.util.Objects.requireNonNull(toolDigest, "toolDigest");
        java.util.Objects.requireNonNull(packageDigest, "packageDigest");
    }
}
