// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Reviewed request blueprint. Values are constants or closed $arg JSON-pointer references; resolved argument data is never interpreted as a template.
 *
 * @param path canonical path value
 * @param query canonical query value
 * @param headers canonical headers value
 * @param contentType canonical contentType value
 * @param bodyMode canonical bodyMode value
 * @param body canonical body value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record EffectIntentBlueprint(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "path", required = true) com.fasterxml.jackson.databind.JsonNode path,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "query", required = true) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> query,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "headers", required = true) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> headers,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "contentType", required = true) String contentType,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "bodyMode", required = true) EffectBodyMode bodyMode,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "body", required = true) com.fasterxml.jackson.databind.JsonNode body
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param path canonical path value
     * @param query canonical query value
     * @param headers canonical headers value
     * @param contentType canonical contentType value
     * @param bodyMode canonical bodyMode value
     * @param body canonical body value
     */
    public EffectIntentBlueprint {
        java.util.Objects.requireNonNull(path, "path");
        java.util.Objects.requireNonNull(query, "query");
        query = query == null ? null : java.util.Map.copyOf(query);
        java.util.Objects.requireNonNull(headers, "headers");
        headers = headers == null ? null : java.util.Map.copyOf(headers);
        java.util.Objects.requireNonNull(contentType, "contentType");
        java.util.Objects.requireNonNull(bodyMode, "bodyMode");
        java.util.Objects.requireNonNull(body, "body");
    }
}
