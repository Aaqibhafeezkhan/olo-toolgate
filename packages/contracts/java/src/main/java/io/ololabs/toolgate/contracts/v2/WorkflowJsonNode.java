// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Pure node declaration; per-run capabilities are injected by the runtime, never captured in topology.
 *
 * @param id canonical id value
 * @param kind canonical kind value
 * @param input canonical input value
 * @param agentId canonical agentId value
 * @param toolRef canonical toolRef value
 * @param resultMode canonical resultMode value
 * @param routes canonical routes value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowJsonNode(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "id", required = true) String id,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "kind", required = true) WorkflowJsonNodeKind kind,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "input", required = true) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> input,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "agentId", required = false) String agentId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toolRef", required = false) String toolRef,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "resultMode", required = false) WorkflowAgentResultMode resultMode,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "routes", required = false) WorkflowJsonBranchRoutes routes
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param id canonical id value
     * @param kind canonical kind value
     * @param input canonical input value
     * @param agentId canonical agentId value
     * @param toolRef canonical toolRef value
     * @param resultMode canonical resultMode value
     * @param routes canonical routes value
     */
    public WorkflowJsonNode {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(kind, "kind");
        java.util.Objects.requireNonNull(input, "input");
        input = input == null ? null : java.util.Map.copyOf(input);
    }
}
