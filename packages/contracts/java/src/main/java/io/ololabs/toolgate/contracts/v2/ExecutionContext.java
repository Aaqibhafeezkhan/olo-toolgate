// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Correlation context of one execution attempt (ADR 031). executionId and attemptNumber are set by the executor from its permit; the other members are declared by the caller, are never trusted and never affect authorization, routing or policy.
 *
 * @param executionId canonical executionId value
 * @param attemptNumber canonical attemptNumber value
 * @param toolCallId canonical toolCallId value
 * @param sessionId canonical sessionId value
 * @param agentRunId canonical agentRunId value
 * @param parentInvocationId canonical parentInvocationId value
 * @param tracestate canonical tracestate value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ExecutionContext(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "executionId", required = false) String executionId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "attemptNumber", required = false) Long attemptNumber,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toolCallId", required = false) String toolCallId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "sessionId", required = false) String sessionId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "agentRunId", required = false) String agentRunId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "parentInvocationId", required = false) String parentInvocationId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tracestate", required = false) String tracestate
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param executionId canonical executionId value
     * @param attemptNumber canonical attemptNumber value
     * @param toolCallId canonical toolCallId value
     * @param sessionId canonical sessionId value
     * @param agentRunId canonical agentRunId value
     * @param parentInvocationId canonical parentInvocationId value
     * @param tracestate canonical tracestate value
     */
    public ExecutionContext {
    }
}
