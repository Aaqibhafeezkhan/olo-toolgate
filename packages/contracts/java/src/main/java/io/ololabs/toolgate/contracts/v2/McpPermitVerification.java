// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Freshness evidence an invocation permit carries for an MCP_PROXY tool.
 *
 * @param status canonical status value
 * @param approvalSequence canonical approvalSequence value
 * @param checkedAtUnixMs canonical checkedAtUnixMs value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record McpPermitVerification(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "status", required = true) McpVerificationStatus status,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "approvalSequence", required = true) Long approvalSequence,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "checkedAtUnixMs", required = false) Long checkedAtUnixMs
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param status canonical status value
     * @param approvalSequence canonical approvalSequence value
     * @param checkedAtUnixMs canonical checkedAtUnixMs value
     */
    public McpPermitVerification {
        java.util.Objects.requireNonNull(status, "status");
        java.util.Objects.requireNonNull(approvalSequence, "approvalSequence");
    }
}
