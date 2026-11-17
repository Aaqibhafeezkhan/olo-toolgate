// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Versioned, bounded JSON topology for the installed generic LangGraph compiler. Structure alone establishes no execution authority.
 *
 * @param schemaVersion canonical schemaVersion value
 * @param nodes canonical nodes value
 * @param edges canonical edges value
 * @param output canonical output value
 * @param outputSchema canonical outputSchema value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowJsonGraph(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "schemaVersion", required = true) Long schemaVersion,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "nodes", required = true) java.util.List<WorkflowJsonNode> nodes,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "edges", required = true) java.util.List<WorkflowJsonEdge> edges,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "output", required = true) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> output,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "outputSchema", required = true) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> outputSchema
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param schemaVersion canonical schemaVersion value
     * @param nodes canonical nodes value
     * @param edges canonical edges value
     * @param output canonical output value
     * @param outputSchema canonical outputSchema value
     */
    public WorkflowJsonGraph {
        java.util.Objects.requireNonNull(schemaVersion, "schemaVersion");
        if (schemaVersion != null && !java.util.Objects.equals(schemaVersion, 1L)) throw new IllegalArgumentException("schemaVersion must be 1");
        java.util.Objects.requireNonNull(nodes, "nodes");
        nodes = nodes == null ? null : java.util.List.copyOf(nodes);
        java.util.Objects.requireNonNull(edges, "edges");
        edges = edges == null ? null : java.util.List.copyOf(edges);
        java.util.Objects.requireNonNull(output, "output");
        output = output == null ? null : java.util.Map.copyOf(output);
        java.util.Objects.requireNonNull(outputSchema, "outputSchema");
        outputSchema = outputSchema == null ? null : java.util.Map.copyOf(outputSchema);
    }
}
