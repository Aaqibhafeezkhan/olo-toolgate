// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** A tool of an approval.
 *
 * @param toolId canonical toolId value
 * @param upstreamName canonical upstreamName value
 * @param definitionDigest canonical definitionDigest value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record McpApprovedTool(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toolId", required = true) String toolId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "upstreamName", required = true) String upstreamName,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "definitionDigest", required = true) String definitionDigest
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param toolId canonical toolId value
     * @param upstreamName canonical upstreamName value
     * @param definitionDigest canonical definitionDigest value
     */
    public McpApprovedTool {
        java.util.Objects.requireNonNull(toolId, "toolId");
        java.util.Objects.requireNonNull(upstreamName, "upstreamName");
        java.util.Objects.requireNonNull(definitionDigest, "definitionDigest");
    }
}
