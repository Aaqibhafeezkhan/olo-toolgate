// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Sent by the lease holder of exactly one invocation (a call) or one scan (a refresh); Control derives the targets from that evidence and applies it only to the current approval.
 *
 * @param invocationId canonical invocationId value
 * @param scanId canonical scanId value
 * @param fencingToken canonical fencingToken value
 * @param reason canonical reason value
 * @param observedDigest canonical observedDigest value
 * @param observedServerInfo canonical observedServerInfo value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record McpQuarantineReport(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "invocationId", required = false) String invocationId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "scanId", required = false) String scanId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "fencingToken", required = true) String fencingToken,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "reason", required = true) McpQuarantineReason reason,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "observedDigest", required = false) String observedDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "observedServerInfo", required = false) McpServerInfo observedServerInfo
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param invocationId canonical invocationId value
     * @param scanId canonical scanId value
     * @param fencingToken canonical fencingToken value
     * @param reason canonical reason value
     * @param observedDigest canonical observedDigest value
     * @param observedServerInfo canonical observedServerInfo value
     */
    public McpQuarantineReport {
        java.util.Objects.requireNonNull(fencingToken, "fencingToken");
        java.util.Objects.requireNonNull(reason, "reason");
    }
}
