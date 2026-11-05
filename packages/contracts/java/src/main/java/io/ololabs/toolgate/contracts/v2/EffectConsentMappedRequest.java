// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Control-reconstructed outbound business request. Contains resolved mapped values, never credentials. Native MCP excludes handler ID and opaque session/protocol headers; these remain bound by the execution grant.
 *
 * @param kind canonical kind value
 * @param routeId canonical routeId value
 * @param classification canonical classification value
 * @param scheme canonical scheme value
 * @param host canonical host value
 * @param port canonical port value
 * @param method canonical method value
 * @param path canonical path value
 * @param query canonical query value
 * @param headers canonical headers value
 * @param contentType canonical contentType value
 * @param bodyMode canonical bodyMode value
 * @param body canonical body value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record EffectConsentMappedRequest(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "kind", required = true) EffectConsentMappingKind kind,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "routeId", required = true) String routeId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "classification", required = true) EffectClassification classification,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "scheme", required = true) String scheme,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "host", required = true) String host,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "port", required = true) Long port,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "method", required = true) String method,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "path", required = true) String path,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "query", required = true) String query,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "headers", required = true) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> headers,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "contentType", required = true) String contentType,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "bodyMode", required = true) EffectBodyMode bodyMode,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "body", required = true) com.fasterxml.jackson.databind.JsonNode body
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param kind canonical kind value
     * @param routeId canonical routeId value
     * @param classification canonical classification value
     * @param scheme canonical scheme value
     * @param host canonical host value
     * @param port canonical port value
     * @param method canonical method value
     * @param path canonical path value
     * @param query canonical query value
     * @param headers canonical headers value
     * @param contentType canonical contentType value
     * @param bodyMode canonical bodyMode value
     * @param body canonical body value
     */
    public EffectConsentMappedRequest {
        java.util.Objects.requireNonNull(kind, "kind");
        java.util.Objects.requireNonNull(routeId, "routeId");
        java.util.Objects.requireNonNull(classification, "classification");
        java.util.Objects.requireNonNull(scheme, "scheme");
        if (scheme != null && !java.util.Objects.equals(scheme, "https")) throw new IllegalArgumentException("scheme must be https");
        java.util.Objects.requireNonNull(host, "host");
        java.util.Objects.requireNonNull(port, "port");
        java.util.Objects.requireNonNull(method, "method");
        java.util.Objects.requireNonNull(path, "path");
        java.util.Objects.requireNonNull(query, "query");
        java.util.Objects.requireNonNull(headers, "headers");
        headers = headers == null ? null : java.util.Map.copyOf(headers);
        java.util.Objects.requireNonNull(contentType, "contentType");
        java.util.Objects.requireNonNull(bodyMode, "bodyMode");
        java.util.Objects.requireNonNull(body, "body");
    }
}
