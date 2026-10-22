// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Upstream credential for scans and calls. The secret is a tenant vault entry whose Tool Group and Device Group equal the registration binding.
 *
 * @param secretName canonical secretName value
 * @param scheme canonical scheme value
 * @param header canonical header value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record McpUpstreamAuth(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "secretName", required = true) String secretName,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "scheme", required = true) McpAuthScheme scheme,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "header", required = false) String header
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param secretName canonical secretName value
     * @param scheme canonical scheme value
     * @param header canonical header value
     */
    public McpUpstreamAuth {
        java.util.Objects.requireNonNull(secretName, "secretName");
        java.util.Objects.requireNonNull(scheme, "scheme");
    }
}
