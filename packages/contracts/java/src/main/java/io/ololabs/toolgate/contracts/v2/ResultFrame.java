// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Terminal output frame.
 *
 * @param kind canonical kind value
 * @param resultType canonical resultType value
 * @param content canonical content value
 * @param structuredContent canonical structuredContent value
 * @param isError canonical isError value
 * @param error canonical error value
 * @param provenance canonical provenance value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ResultFrame(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "kind", required = true) String kind,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "resultType", required = true) ResultType resultType,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "content", required = true) java.util.List<java.util.Map<String, com.fasterxml.jackson.databind.JsonNode>> content,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "structuredContent", required = false) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> structuredContent,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "isError", required = true) Boolean isError,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "error", required = false) ToolErrorBody error,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "provenance", required = false) ResultProvenance provenance
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param kind canonical kind value
     * @param resultType canonical resultType value
     * @param content canonical content value
     * @param structuredContent canonical structuredContent value
     * @param isError canonical isError value
     * @param error canonical error value
     * @param provenance canonical provenance value
     */
    public ResultFrame {
        java.util.Objects.requireNonNull(kind, "kind");
        if (kind != null && !java.util.Objects.equals(kind, "RESULT")) throw new IllegalArgumentException("kind must be RESULT");
        java.util.Objects.requireNonNull(resultType, "resultType");
        java.util.Objects.requireNonNull(content, "content");
        content = content == null ? null : java.util.List.copyOf(content);
        structuredContent = structuredContent == null ? null : java.util.Map.copyOf(structuredContent);
        java.util.Objects.requireNonNull(isError, "isError");
    }
}
