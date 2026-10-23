// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Registers an upstream MCP server in the routed tenant. The pool is the binding Device Group.
 *
 * @param slug canonical slug value
 * @param url canonical url value
 * @param auth canonical auth value
 * @param bindingId canonical bindingId value
 * @param strict canonical strict value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record McpServerRegistrationRequest(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "slug", required = true) String slug,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "url", required = true) String url,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "auth", required = true) McpUpstreamAuth auth,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "bindingId", required = true) String bindingId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "strict", required = true) Boolean strict
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param slug canonical slug value
     * @param url canonical url value
     * @param auth canonical auth value
     * @param bindingId canonical bindingId value
     * @param strict canonical strict value
     */
    public McpServerRegistrationRequest {
        java.util.Objects.requireNonNull(slug, "slug");
        java.util.Objects.requireNonNull(url, "url");
        java.util.Objects.requireNonNull(auth, "auth");
        java.util.Objects.requireNonNull(bindingId, "bindingId");
        java.util.Objects.requireNonNull(strict, "strict");
    }
}
