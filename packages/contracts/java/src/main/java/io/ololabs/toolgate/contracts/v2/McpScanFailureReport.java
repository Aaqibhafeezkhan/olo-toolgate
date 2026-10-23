// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** A failed scan, reported by the lease holder before the deadline.
 *
 * @param scanId canonical scanId value
 * @param fencingToken canonical fencingToken value
 * @param failure canonical failure value
 * @param observedSpkiPin canonical observedSpkiPin value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record McpScanFailureReport(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "scanId", required = true) String scanId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "fencingToken", required = true) String fencingToken,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "failure", required = true) McpCheckFailure failure,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "observedSpkiPin", required = false) String observedSpkiPin
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param scanId canonical scanId value
     * @param fencingToken canonical fencingToken value
     * @param failure canonical failure value
     * @param observedSpkiPin canonical observedSpkiPin value
     */
    public McpScanFailureReport {
        java.util.Objects.requireNonNull(scanId, "scanId");
        java.util.Objects.requireNonNull(fencingToken, "fencingToken");
        java.util.Objects.requireNonNull(failure, "failure");
    }
}
