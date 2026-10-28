// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** A snapshot tool: the upstream definition and its definition digest (tool-sdk/conformance/mcp-proxy).
 *
 * @param tool canonical tool value
 * @param definitionDigest canonical definitionDigest value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record McpToolDefinition(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tool", required = true) McpUpstreamTool tool,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "definitionDigest", required = true) String definitionDigest
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param tool canonical tool value
     * @param definitionDigest canonical definitionDigest value
     */
    public McpToolDefinition {
        java.util.Objects.requireNonNull(tool, "tool");
        java.util.Objects.requireNonNull(definitionDigest, "definitionDigest");
    }
}
