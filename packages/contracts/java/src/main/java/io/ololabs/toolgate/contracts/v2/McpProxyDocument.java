// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** mcp-proxy.json, the one artifact of an MCP_PROXY package (media type application/vnd.ololabs.toolgate.mcp-proxy+json).
 *
 * @param formatVersion canonical formatVersion value
 * @param server canonical server value
 * @param approvalSequence canonical approvalSequence value
 * @param strict canonical strict value
 * @param tools canonical tools value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record McpProxyDocument(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "formatVersion", required = true) Long formatVersion,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "server", required = true) McpProxyServer server,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "approvalSequence", required = true) Long approvalSequence,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "strict", required = true) Boolean strict,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tools", required = true) java.util.List<McpApprovedTool> tools
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param formatVersion canonical formatVersion value
     * @param server canonical server value
     * @param approvalSequence canonical approvalSequence value
     * @param strict canonical strict value
     * @param tools canonical tools value
     */
    public McpProxyDocument {
        java.util.Objects.requireNonNull(formatVersion, "formatVersion");
        if (formatVersion != null && !java.util.Objects.equals(formatVersion, 1L)) throw new IllegalArgumentException("formatVersion must be 1");
        java.util.Objects.requireNonNull(server, "server");
        java.util.Objects.requireNonNull(approvalSequence, "approvalSequence");
        java.util.Objects.requireNonNull(strict, "strict");
        java.util.Objects.requireNonNull(tools, "tools");
        tools = tools == null ? null : java.util.List.copyOf(tools);
    }
}
