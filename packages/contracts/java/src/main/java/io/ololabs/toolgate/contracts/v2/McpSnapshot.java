// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** An immutable scan result; Control recomputes every digest.
 *
 * @param revision canonical revision value
 * @param scanId canonical scanId value
 * @param serverInfo canonical serverInfo value
 * @param protocolVersion canonical protocolVersion value
 * @param spkiPin canonical spkiPin value
 * @param scannedAtUnixMs canonical scannedAtUnixMs value
 * @param tools canonical tools value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record McpSnapshot(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "revision", required = true) Long revision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "scanId", required = true) String scanId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "serverInfo", required = true) McpServerInfo serverInfo,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "protocolVersion", required = true) String protocolVersion,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "spkiPin", required = true) String spkiPin,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "scannedAtUnixMs", required = true) Long scannedAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tools", required = true) java.util.List<McpToolDefinition> tools
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param revision canonical revision value
     * @param scanId canonical scanId value
     * @param serverInfo canonical serverInfo value
     * @param protocolVersion canonical protocolVersion value
     * @param spkiPin canonical spkiPin value
     * @param scannedAtUnixMs canonical scannedAtUnixMs value
     * @param tools canonical tools value
     */
    public McpSnapshot {
        java.util.Objects.requireNonNull(revision, "revision");
        java.util.Objects.requireNonNull(scanId, "scanId");
        java.util.Objects.requireNonNull(serverInfo, "serverInfo");
        java.util.Objects.requireNonNull(protocolVersion, "protocolVersion");
        java.util.Objects.requireNonNull(spkiPin, "spkiPin");
        java.util.Objects.requireNonNull(scannedAtUnixMs, "scannedAtUnixMs");
        java.util.Objects.requireNonNull(tools, "tools");
        tools = tools == null ? null : java.util.List.copyOf(tools);
    }
}
