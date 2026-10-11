// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Declared HTTP route, without host values.
 *
 * @param id canonical id value
 * @param destination canonical destination value
 * @param method canonical method value
 * @param path canonical path value
 * @param query canonical query value
 * @param headers canonical headers value
 * @param requestContentTypes canonical requestContentTypes value
 * @param maxRequestBytes canonical maxRequestBytes value
 * @param maxResponseBytes canonical maxResponseBytes value
 * @param streaming canonical streaming value
 * @param timeoutMs canonical timeoutMs value
 * @param auth canonical auth value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record RouteDeclaration(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "id", required = true) String id,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "destination", required = true) String destination,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "method", required = true) HttpMethod method,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "path", required = true) String path,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "query", required = true) java.util.List<String> query,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "headers", required = true) java.util.List<String> headers,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "requestContentTypes", required = true) java.util.List<String> requestContentTypes,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "maxRequestBytes", required = true) Long maxRequestBytes,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "maxResponseBytes", required = true) Long maxResponseBytes,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "streaming", required = true) Streaming streaming,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "timeoutMs", required = true) Long timeoutMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "auth", required = false) String auth
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param id canonical id value
     * @param destination canonical destination value
     * @param method canonical method value
     * @param path canonical path value
     * @param query canonical query value
     * @param headers canonical headers value
     * @param requestContentTypes canonical requestContentTypes value
     * @param maxRequestBytes canonical maxRequestBytes value
     * @param maxResponseBytes canonical maxResponseBytes value
     * @param streaming canonical streaming value
     * @param timeoutMs canonical timeoutMs value
     * @param auth canonical auth value
     */
    public RouteDeclaration {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(destination, "destination");
        java.util.Objects.requireNonNull(method, "method");
        java.util.Objects.requireNonNull(path, "path");
        java.util.Objects.requireNonNull(query, "query");
        query = query == null ? null : java.util.List.copyOf(query);
        java.util.Objects.requireNonNull(headers, "headers");
        headers = headers == null ? null : java.util.List.copyOf(headers);
        java.util.Objects.requireNonNull(requestContentTypes, "requestContentTypes");
        requestContentTypes = requestContentTypes == null ? null : java.util.List.copyOf(requestContentTypes);
        java.util.Objects.requireNonNull(maxRequestBytes, "maxRequestBytes");
        java.util.Objects.requireNonNull(maxResponseBytes, "maxResponseBytes");
        java.util.Objects.requireNonNull(streaming, "streaming");
        java.util.Objects.requireNonNull(timeoutMs, "timeoutMs");
    }
}
