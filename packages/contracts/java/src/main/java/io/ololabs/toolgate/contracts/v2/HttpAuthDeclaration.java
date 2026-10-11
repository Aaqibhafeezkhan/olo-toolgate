// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Declared HTTP auth.
 *
 * @param id canonical id value
 * @param type canonical type value
 * @param header canonical header value
 * @param secret canonical secret value
 * @param connection canonical connection value
 * @param scopes canonical scopes value
 * @param audience canonical audience value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record HttpAuthDeclaration(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "id", required = true) String id,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "type", required = true) AuthType type,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "header", required = false) String header,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "secret", required = false) String secret,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "connection", required = false) String connection,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "scopes", required = true) java.util.List<String> scopes,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "audience", required = false) String audience
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param id canonical id value
     * @param type canonical type value
     * @param header canonical header value
     * @param secret canonical secret value
     * @param connection canonical connection value
     * @param scopes canonical scopes value
     * @param audience canonical audience value
     */
    public HttpAuthDeclaration {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(type, "type");
        java.util.Objects.requireNonNull(scopes, "scopes");
        scopes = scopes == null ? null : java.util.List.copyOf(scopes);
    }
}
