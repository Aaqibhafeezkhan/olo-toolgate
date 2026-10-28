// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** What the scanning host observed, reported once by the lease holder before the deadline.
 *
 * @param scanId canonical scanId value
 * @param fencingToken canonical fencingToken value
 * @param serverInfo canonical serverInfo value
 * @param protocolVersion canonical protocolVersion value
 * @param spkiPin canonical spkiPin value
 * @param tools canonical tools value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record McpScanResult(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "scanId", required = true) String scanId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "fencingToken", required = true) String fencingToken,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "serverInfo", required = true) McpServerInfo serverInfo,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "protocolVersion", required = true) String protocolVersion,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "spkiPin", required = true) String spkiPin,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tools", required = true) java.util.List<McpUpstreamTool> tools
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param scanId canonical scanId value
     * @param fencingToken canonical fencingToken value
     * @param serverInfo canonical serverInfo value
     * @param protocolVersion canonical protocolVersion value
     * @param spkiPin canonical spkiPin value
     * @param tools canonical tools value
     */
    public McpScanResult {
        java.util.Objects.requireNonNull(scanId, "scanId");
        java.util.Objects.requireNonNull(fencingToken, "fencingToken");
        java.util.Objects.requireNonNull(serverInfo, "serverInfo");
        java.util.Objects.requireNonNull(protocolVersion, "protocolVersion");
        java.util.Objects.requireNonNull(spkiPin, "spkiPin");
        java.util.Objects.requireNonNull(tools, "tools");
        tools = tools == null ? null : java.util.List.copyOf(tools);
    }
}
