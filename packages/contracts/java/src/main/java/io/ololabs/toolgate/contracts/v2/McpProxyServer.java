// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** The upstream an MCP_PROXY package proxies.
 *
 * @param slug canonical slug value
 * @param serverInfo canonical serverInfo value
 * @param spkiPins canonical spkiPins value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record McpProxyServer(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "slug", required = true) String slug,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "serverInfo", required = true) McpServerInfo serverInfo,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "spkiPins", required = true) java.util.List<String> spkiPins
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param slug canonical slug value
     * @param serverInfo canonical serverInfo value
     * @param spkiPins canonical spkiPins value
     */
    public McpProxyServer {
        java.util.Objects.requireNonNull(slug, "slug");
        java.util.Objects.requireNonNull(serverInfo, "serverInfo");
        java.util.Objects.requireNonNull(spkiPins, "spkiPins");
        spkiPins = spkiPins == null ? null : java.util.List.copyOf(spkiPins);
    }
}
