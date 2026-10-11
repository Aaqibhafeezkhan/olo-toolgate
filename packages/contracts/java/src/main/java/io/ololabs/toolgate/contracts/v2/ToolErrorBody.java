// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Error per tool-sdk/spec/authoring/errors.md.
 *
 * @param code canonical code value
 * @param message canonical message value
 * @param retryable canonical retryable value
 * @param data canonical data value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ToolErrorBody(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "code", required = true) String code,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "message", required = true) String message,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "retryable", required = true) Boolean retryable,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "data", required = false) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> data
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param code canonical code value
     * @param message canonical message value
     * @param retryable canonical retryable value
     * @param data canonical data value
     */
    public ToolErrorBody {
        java.util.Objects.requireNonNull(code, "code");
        java.util.Objects.requireNonNull(message, "message");
        java.util.Objects.requireNonNull(retryable, "retryable");
        data = data == null ? null : java.util.Map.copyOf(data);
    }
}
