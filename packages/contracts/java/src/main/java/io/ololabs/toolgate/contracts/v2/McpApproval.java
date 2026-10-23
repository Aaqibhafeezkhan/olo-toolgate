// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** An accepted approval: one per approval sequence, with its generated package.
 *
 * @param sequence canonical sequence value
 * @param snapshotRevision canonical snapshotRevision value
 * @param tools canonical tools value
 * @param spkiPins canonical spkiPins value
 * @param packageId canonical packageId value
 * @param packageVersion canonical packageVersion value
 * @param packageDigest canonical packageDigest value
 * @param approvedAtUnixMs canonical approvedAtUnixMs value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record McpApproval(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "sequence", required = true) Long sequence,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "snapshotRevision", required = true) Long snapshotRevision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tools", required = true) java.util.List<McpApprovedTool> tools,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "spkiPins", required = true) java.util.List<String> spkiPins,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageId", required = true) String packageId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageVersion", required = true) String packageVersion,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageDigest", required = true) String packageDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "approvedAtUnixMs", required = true) Long approvedAtUnixMs
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param sequence canonical sequence value
     * @param snapshotRevision canonical snapshotRevision value
     * @param tools canonical tools value
     * @param spkiPins canonical spkiPins value
     * @param packageId canonical packageId value
     * @param packageVersion canonical packageVersion value
     * @param packageDigest canonical packageDigest value
     * @param approvedAtUnixMs canonical approvedAtUnixMs value
     */
    public McpApproval {
        java.util.Objects.requireNonNull(sequence, "sequence");
        java.util.Objects.requireNonNull(snapshotRevision, "snapshotRevision");
        java.util.Objects.requireNonNull(tools, "tools");
        tools = tools == null ? null : java.util.List.copyOf(tools);
        java.util.Objects.requireNonNull(spkiPins, "spkiPins");
        spkiPins = spkiPins == null ? null : java.util.List.copyOf(spkiPins);
        java.util.Objects.requireNonNull(packageId, "packageId");
        java.util.Objects.requireNonNull(packageVersion, "packageVersion");
        java.util.Objects.requireNonNull(packageDigest, "packageDigest");
        java.util.Objects.requireNonNull(approvedAtUnixMs, "approvedAtUnixMs");
    }
}
