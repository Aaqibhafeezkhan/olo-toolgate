// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Executor to tool on stdin: the result of the outstanding brokered operation, or a canonical error. Nothing else is written to the tool after its ToolInput.
 *
 * @param kind canonical kind value
 * @param requestId canonical requestId value
 * @param result canonical result value
 * @param error canonical error value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record BrokerResponseFrame(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "kind", required = true) String kind,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "requestId", required = true) String requestId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "result", required = false) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> result,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "error", required = false) ToolErrorBody error
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param kind canonical kind value
     * @param requestId canonical requestId value
     * @param result canonical result value
     * @param error canonical error value
     */
    public BrokerResponseFrame {
        java.util.Objects.requireNonNull(kind, "kind");
        if (kind != null && !java.util.Objects.equals(kind, "BROKER_RESPONSE")) throw new IllegalArgumentException("kind must be BROKER_RESPONSE");
        java.util.Objects.requireNonNull(requestId, "requestId");
        result = result == null ? null : java.util.Map.copyOf(result);
    }
}
