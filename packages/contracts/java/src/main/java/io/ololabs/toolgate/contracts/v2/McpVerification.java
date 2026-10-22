// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** The latest check of a registration against its approval; a failure replaces an earlier success.
 *
 * @param status canonical status value
 * @param approvalSequence canonical approvalSequence value
 * @param checkedAtUnixMs canonical checkedAtUnixMs value
 * @param failure canonical failure value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record McpVerification(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "status", required = true) McpVerificationStatus status,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "approvalSequence", required = false) Long approvalSequence,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "checkedAtUnixMs", required = false) Long checkedAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "failure", required = false) McpCheckFailure failure
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param status canonical status value
     * @param approvalSequence canonical approvalSequence value
     * @param checkedAtUnixMs canonical checkedAtUnixMs value
     * @param failure canonical failure value
     */
    public McpVerification {
        java.util.Objects.requireNonNull(status, "status");
    }
}
