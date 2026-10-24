// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Capability definition. JSON schemas are data, never executable code.
 *
 * @param id canonical id value
 * @param name canonical name value
 * @param description canonical description value
 * @param actions canonical actions value
 * @param inputSchema canonical inputSchema value
 * @param outputSchema canonical outputSchema value
 * @param reconciliation canonical reconciliation value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ToolDefinition(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "id", required = true) String id,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "name", required = true) String name,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "description", required = true) String description,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "actions", required = true) java.util.List<ToolAction> actions,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "inputSchema", required = true) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> inputSchema,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "outputSchema", required = true) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> outputSchema,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "reconciliation", required = false) ReconciliationMethod reconciliation
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param id canonical id value
     * @param name canonical name value
     * @param description canonical description value
     * @param actions canonical actions value
     * @param inputSchema canonical inputSchema value
     * @param outputSchema canonical outputSchema value
     * @param reconciliation canonical reconciliation value
     */
    public ToolDefinition {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(name, "name");
        java.util.Objects.requireNonNull(description, "description");
        java.util.Objects.requireNonNull(actions, "actions");
        actions = actions == null ? null : java.util.List.copyOf(actions);
        java.util.Objects.requireNonNull(inputSchema, "inputSchema");
        inputSchema = inputSchema == null ? null : java.util.Map.copyOf(inputSchema);
        java.util.Objects.requireNonNull(outputSchema, "outputSchema");
        outputSchema = outputSchema == null ? null : java.util.Map.copyOf(outputSchema);
    }
}
