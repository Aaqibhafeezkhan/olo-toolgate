// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** One invocation's summary, projected from its transition facts. Durations are absent when not measured.
 *
 * @param tenantId canonical tenantId value
 * @param invocationId canonical invocationId value
 * @param toolId canonical toolId value
 * @param action canonical action value
 * @param clientId canonical clientId value
 * @param userId canonical userId value
 * @param agentId canonical agentId value
 * @param toolCallId canonical toolCallId value
 * @param sessionId canonical sessionId value
 * @param agentRunId canonical agentRunId value
 * @param parentInvocationId canonical parentInvocationId value
 * @param outcome canonical outcome value
 * @param errorCategory canonical errorCategory value
 * @param attempts canonical attempts value
 * @param acceptedAtUnixMs canonical acceptedAtUnixMs value
 * @param approvalRequestedAtUnixMs canonical approvalRequestedAtUnixMs value
 * @param approvalResolvedAtUnixMs canonical approvalResolvedAtUnixMs value
 * @param queuedAtUnixMs canonical queuedAtUnixMs value
 * @param firstStartedAtUnixMs canonical firstStartedAtUnixMs value
 * @param terminalAtUnixMs canonical terminalAtUnixMs value
 * @param approvalMs canonical approvalMs value
 * @param queueMs canonical queueMs value
 * @param executionMs canonical executionMs value
 * @param endToEndMs canonical endToEndMs value
 * @param partialCoverage canonical partialCoverage value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record InsightsInvocation(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tenantId", required = true) String tenantId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "invocationId", required = true) String invocationId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toolId", required = true) String toolId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "action", required = true) String action,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "clientId", required = false) String clientId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "userId", required = false) String userId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "agentId", required = false) String agentId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toolCallId", required = false) String toolCallId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "sessionId", required = false) String sessionId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "agentRunId", required = false) String agentRunId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "parentInvocationId", required = false) String parentInvocationId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "outcome", required = true) InsightsOutcome outcome,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "errorCategory", required = false) InsightsErrorCategory errorCategory,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "attempts", required = true) Long attempts,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "acceptedAtUnixMs", required = true) Long acceptedAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "approvalRequestedAtUnixMs", required = false) Long approvalRequestedAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "approvalResolvedAtUnixMs", required = false) Long approvalResolvedAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "queuedAtUnixMs", required = false) Long queuedAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "firstStartedAtUnixMs", required = false) Long firstStartedAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "terminalAtUnixMs", required = false) Long terminalAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "approvalMs", required = false) Long approvalMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "queueMs", required = false) Long queueMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "executionMs", required = false) Long executionMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "endToEndMs", required = false) Long endToEndMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "partialCoverage", required = true) Boolean partialCoverage
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param tenantId canonical tenantId value
     * @param invocationId canonical invocationId value
     * @param toolId canonical toolId value
     * @param action canonical action value
     * @param clientId canonical clientId value
     * @param userId canonical userId value
     * @param agentId canonical agentId value
     * @param toolCallId canonical toolCallId value
     * @param sessionId canonical sessionId value
     * @param agentRunId canonical agentRunId value
     * @param parentInvocationId canonical parentInvocationId value
     * @param outcome canonical outcome value
     * @param errorCategory canonical errorCategory value
     * @param attempts canonical attempts value
     * @param acceptedAtUnixMs canonical acceptedAtUnixMs value
     * @param approvalRequestedAtUnixMs canonical approvalRequestedAtUnixMs value
     * @param approvalResolvedAtUnixMs canonical approvalResolvedAtUnixMs value
     * @param queuedAtUnixMs canonical queuedAtUnixMs value
     * @param firstStartedAtUnixMs canonical firstStartedAtUnixMs value
     * @param terminalAtUnixMs canonical terminalAtUnixMs value
     * @param approvalMs canonical approvalMs value
     * @param queueMs canonical queueMs value
     * @param executionMs canonical executionMs value
     * @param endToEndMs canonical endToEndMs value
     * @param partialCoverage canonical partialCoverage value
     */
    public InsightsInvocation {
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        java.util.Objects.requireNonNull(invocationId, "invocationId");
        java.util.Objects.requireNonNull(toolId, "toolId");
        java.util.Objects.requireNonNull(action, "action");
        java.util.Objects.requireNonNull(outcome, "outcome");
        java.util.Objects.requireNonNull(attempts, "attempts");
        java.util.Objects.requireNonNull(acceptedAtUnixMs, "acceptedAtUnixMs");
        java.util.Objects.requireNonNull(partialCoverage, "partialCoverage");
    }
}
