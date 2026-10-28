// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Approves tools of one snapshot; at most 64, the package and deployment limit.
 *
 * @param snapshotRevision canonical snapshotRevision value
 * @param tools canonical tools value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record McpApprovalRequest(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "snapshotRevision", required = true) Long snapshotRevision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tools", required = true) java.util.List<McpToolApproval> tools
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param snapshotRevision canonical snapshotRevision value
     * @param tools canonical tools value
     */
    public McpApprovalRequest {
        java.util.Objects.requireNonNull(snapshotRevision, "snapshotRevision");
        java.util.Objects.requireNonNull(tools, "tools");
        tools = tools == null ? null : java.util.List.copyOf(tools);
    }
}
