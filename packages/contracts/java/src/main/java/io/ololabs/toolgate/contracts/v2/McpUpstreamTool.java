// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** One tool exactly as the upstream tools/list returned it, within the scan limits.
 *
 * @param name canonical name value
 * @param title canonical title value
 * @param description canonical description value
 * @param inputSchema canonical inputSchema value
 * @param outputSchema canonical outputSchema value
 * @param annotations canonical annotations value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record McpUpstreamTool(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "name", required = true) String name,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "title", required = false) String title,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "description", required = false) String description,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "inputSchema", required = true) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> inputSchema,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "outputSchema", required = false) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> outputSchema,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "annotations", required = false) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> annotations
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param name canonical name value
     * @param title canonical title value
     * @param description canonical description value
     * @param inputSchema canonical inputSchema value
     * @param outputSchema canonical outputSchema value
     * @param annotations canonical annotations value
     */
    public McpUpstreamTool {
        java.util.Objects.requireNonNull(name, "name");
        java.util.Objects.requireNonNull(inputSchema, "inputSchema");
        inputSchema = inputSchema == null ? null : java.util.Map.copyOf(inputSchema);
        outputSchema = outputSchema == null ? null : java.util.Map.copyOf(outputSchema);
        annotations = annotations == null ? null : java.util.Map.copyOf(annotations);
    }
}
