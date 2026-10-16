// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Local tool boundary for signed managed registrations; validate schema before use.
 *
 * @param requestId canonical requestId value
 * @param tools canonical tools value
 * @param output canonical output value
 * @param error canonical error value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record LocalToolIpcResponse(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "requestId", required = true) String requestId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tools", required = false) java.util.List<LocalToolInfo> tools,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "output", required = false) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> output,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "error", required = false) ErrorCode error
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param requestId canonical requestId value
     * @param tools canonical tools value
     * @param output canonical output value
     * @param error canonical error value
     */
    public LocalToolIpcResponse {
        java.util.Objects.requireNonNull(requestId, "requestId");
        tools = tools == null ? null : java.util.List.copyOf(tools);
        output = output == null ? null : java.util.Map.copyOf(output);
    }
}
