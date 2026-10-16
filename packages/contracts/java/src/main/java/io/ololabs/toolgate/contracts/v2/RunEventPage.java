// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Authorized replay page after a cursor. terminal is the authoritative ledger state; truncated, droppedFrames, gaps and expired say explicitly what is not shown.
 *
 * @param invocationId canonical invocationId value
 * @param state canonical state value
 * @param terminal canonical terminal value
 * @param toolId canonical toolId value
 * @param executorId canonical executorId value
 * @param events canonical events value
 * @param nextCursor canonical nextCursor value
 * @param more canonical more value
 * @param truncated canonical truncated value
 * @param droppedFrames canonical droppedFrames value
 * @param expired canonical expired value
 * @param gaps canonical gaps value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record RunEventPage(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "invocationId", required = true) String invocationId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "state", required = true) String state,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "terminal", required = true) Boolean terminal,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toolId", required = false) String toolId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "executorId", required = false) String executorId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "events", required = true) java.util.List<RunEvent> events,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "nextCursor", required = true) Long nextCursor,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "more", required = true) Boolean more,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "truncated", required = true) Boolean truncated,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "droppedFrames", required = true) Long droppedFrames,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "expired", required = true) Boolean expired,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "gaps", required = true) java.util.List<RunEventGap> gaps
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param invocationId canonical invocationId value
     * @param state canonical state value
     * @param terminal canonical terminal value
     * @param toolId canonical toolId value
     * @param executorId canonical executorId value
     * @param events canonical events value
     * @param nextCursor canonical nextCursor value
     * @param more canonical more value
     * @param truncated canonical truncated value
     * @param droppedFrames canonical droppedFrames value
     * @param expired canonical expired value
     * @param gaps canonical gaps value
     */
    public RunEventPage {
        java.util.Objects.requireNonNull(invocationId, "invocationId");
        java.util.Objects.requireNonNull(state, "state");
        java.util.Objects.requireNonNull(terminal, "terminal");
        java.util.Objects.requireNonNull(events, "events");
        events = events == null ? null : java.util.List.copyOf(events);
        java.util.Objects.requireNonNull(nextCursor, "nextCursor");
        java.util.Objects.requireNonNull(more, "more");
        java.util.Objects.requireNonNull(truncated, "truncated");
        java.util.Objects.requireNonNull(droppedFrames, "droppedFrames");
        java.util.Objects.requireNonNull(expired, "expired");
        java.util.Objects.requireNonNull(gaps, "gaps");
        gaps = gaps == null ? null : java.util.List.copyOf(gaps);
    }
}
