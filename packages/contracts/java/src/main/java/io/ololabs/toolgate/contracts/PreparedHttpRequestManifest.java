// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Credential-free toolgate-http-request-v1 manifest of the exact prepared wire request. Never conveys authority on its own.
 *
 * @param version canonical version value
 * @param profileId canonical profileId value
 * @param profileDigest canonical profileDigest value
 * @param routeId canonical routeId value
 * @param scheme canonical scheme value
 * @param host canonical host value
 * @param port canonical port value
 * @param method canonical method value
 * @param path canonical path value
 * @param query canonical query value
 * @param contentType canonical contentType value
 * @param bodyDigest canonical bodyDigest value
 * @param headers canonical headers value
 * @param authDeclarationId canonical authDeclarationId value
 * @param credentialHeaders canonical credentialHeaders value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record PreparedHttpRequestManifest(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "version", required = true) String version,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "profileId", required = true) String profileId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "profileDigest", required = true) String profileDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "routeId", required = true) String routeId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "scheme", required = true) String scheme,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "host", required = true) String host,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "port", required = true) Long port,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "method", required = true) String method,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "path", required = true) String path,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "query", required = true) String query,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "contentType", required = true) String contentType,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "bodyDigest", required = true) String bodyDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "headers", required = true) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> headers,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "authDeclarationId", required = true) com.fasterxml.jackson.databind.JsonNode authDeclarationId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "credentialHeaders", required = true) java.util.List<String> credentialHeaders
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param version canonical version value
     * @param profileId canonical profileId value
     * @param profileDigest canonical profileDigest value
     * @param routeId canonical routeId value
     * @param scheme canonical scheme value
     * @param host canonical host value
     * @param port canonical port value
     * @param method canonical method value
     * @param path canonical path value
     * @param query canonical query value
     * @param contentType canonical contentType value
     * @param bodyDigest canonical bodyDigest value
     * @param headers canonical headers value
     * @param authDeclarationId canonical authDeclarationId value
     * @param credentialHeaders canonical credentialHeaders value
     */
    public PreparedHttpRequestManifest {
        java.util.Objects.requireNonNull(version, "version");
        java.util.Objects.requireNonNull(profileId, "profileId");
        java.util.Objects.requireNonNull(profileDigest, "profileDigest");
        java.util.Objects.requireNonNull(routeId, "routeId");
        java.util.Objects.requireNonNull(scheme, "scheme");
        java.util.Objects.requireNonNull(host, "host");
        java.util.Objects.requireNonNull(port, "port");
        java.util.Objects.requireNonNull(method, "method");
        java.util.Objects.requireNonNull(path, "path");
        java.util.Objects.requireNonNull(query, "query");
        java.util.Objects.requireNonNull(contentType, "contentType");
        java.util.Objects.requireNonNull(bodyDigest, "bodyDigest");
        java.util.Objects.requireNonNull(headers, "headers");
        headers = headers == null ? null : java.util.Map.copyOf(headers);
        java.util.Objects.requireNonNull(authDeclarationId, "authDeclarationId");
        java.util.Objects.requireNonNull(credentialHeaders, "credentialHeaders");
        credentialHeaders = credentialHeaders == null ? null : java.util.List.copyOf(credentialHeaders);
    }
}
