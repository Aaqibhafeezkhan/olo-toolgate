// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** A scan of one registration on one Tool Host of its binding Device Group.
 *
 * @param scanId canonical scanId value
 * @param slug canonical slug value
 * @param purpose canonical purpose value
 * @param state canonical state value
 * @param hostId canonical hostId value
 * @param createdAtUnixMs canonical createdAtUnixMs value
 * @param deadlineUnixMs canonical deadlineUnixMs value
 * @param approvalSequence canonical approvalSequence value
 * @param snapshotRevision canonical snapshotRevision value
 * @param failure canonical failure value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record McpScanJob(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "scanId", required = true) String scanId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "slug", required = true) String slug,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "purpose", required = true) McpScanPurpose purpose,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "state", required = true) McpScanState state,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "hostId", required = false) String hostId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "createdAtUnixMs", required = true) Long createdAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deadlineUnixMs", required = true) Long deadlineUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "approvalSequence", required = false) Long approvalSequence,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "snapshotRevision", required = false) Long snapshotRevision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "failure", required = false) McpCheckFailure failure
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param scanId canonical scanId value
     * @param slug canonical slug value
     * @param purpose canonical purpose value
     * @param state canonical state value
     * @param hostId canonical hostId value
     * @param createdAtUnixMs canonical createdAtUnixMs value
     * @param deadlineUnixMs canonical deadlineUnixMs value
     * @param approvalSequence canonical approvalSequence value
     * @param snapshotRevision canonical snapshotRevision value
     * @param failure canonical failure value
     */
    public McpScanJob {
        java.util.Objects.requireNonNull(scanId, "scanId");
        java.util.Objects.requireNonNull(slug, "slug");
        java.util.Objects.requireNonNull(purpose, "purpose");
        java.util.Objects.requireNonNull(state, "state");
        java.util.Objects.requireNonNull(createdAtUnixMs, "createdAtUnixMs");
        java.util.Objects.requireNonNull(deadlineUnixMs, "deadlineUnixMs");
    }
}
