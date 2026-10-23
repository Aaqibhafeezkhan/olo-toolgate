// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** A recorded quarantine and the evidence that caused it.
 *
 * @param reason canonical reason value
 * @param evidenceId canonical evidenceId value
 * @param toolIds canonical toolIds value
 * @param serverWide canonical serverWide value
 * @param atUnixMs canonical atUnixMs value
 * @param approvalSequence canonical approvalSequence value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record McpQuarantine(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "reason", required = true) McpQuarantineReason reason,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "evidenceId", required = true) String evidenceId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toolIds", required = true) java.util.List<String> toolIds,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "serverWide", required = true) Boolean serverWide,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "atUnixMs", required = true) Long atUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "approvalSequence", required = true) Long approvalSequence
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param reason canonical reason value
     * @param evidenceId canonical evidenceId value
     * @param toolIds canonical toolIds value
     * @param serverWide canonical serverWide value
     * @param atUnixMs canonical atUnixMs value
     * @param approvalSequence canonical approvalSequence value
     */
    public McpQuarantine {
        java.util.Objects.requireNonNull(reason, "reason");
        java.util.Objects.requireNonNull(evidenceId, "evidenceId");
        java.util.Objects.requireNonNull(toolIds, "toolIds");
        toolIds = toolIds == null ? null : java.util.List.copyOf(toolIds);
        java.util.Objects.requireNonNull(serverWide, "serverWide");
        java.util.Objects.requireNonNull(atUnixMs, "atUnixMs");
        java.util.Objects.requireNonNull(approvalSequence, "approvalSequence");
    }
}
