// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** A scan a Tool Host fetched: the signed scan permit for its admission.
 *
 * @param scanId canonical scanId value
 * @param permit canonical permit value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record McpScanDispatch(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "scanId", required = true) String scanId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "permit", required = true) String permit
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param scanId canonical scanId value
     * @param permit canonical permit value
     */
    public McpScanDispatch {
        java.util.Objects.requireNonNull(scanId, "scanId");
        java.util.Objects.requireNonNull(permit, "permit");
    }
}
