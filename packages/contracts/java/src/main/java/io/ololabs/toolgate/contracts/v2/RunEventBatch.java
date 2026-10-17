// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Executor to Control: frames of one admitted attempt, bound to the invocation and the fencing token its permit consumption returned. Redelivery of the same sequence is idempotent. droppedFrames counts frames the executor discarded under its own bounds.
 *
 * @param invocationId canonical invocationId value
 * @param fencingToken canonical fencingToken value
 * @param attempt canonical attempt value
 * @param events canonical events value
 * @param droppedFrames canonical droppedFrames value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record RunEventBatch(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "invocationId", required = true) String invocationId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "fencingToken", required = true) String fencingToken,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "attempt", required = true) Long attempt,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "events", required = true) java.util.List<RunEventFrame> events,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "droppedFrames", required = false) Long droppedFrames
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param invocationId canonical invocationId value
     * @param fencingToken canonical fencingToken value
     * @param attempt canonical attempt value
     * @param events canonical events value
     * @param droppedFrames canonical droppedFrames value
     */
    public RunEventBatch {
        java.util.Objects.requireNonNull(invocationId, "invocationId");
        java.util.Objects.requireNonNull(fencingToken, "fencingToken");
        java.util.Objects.requireNonNull(attempt, "attempt");
        java.util.Objects.requireNonNull(events, "events");
        events = events == null ? null : java.util.List.copyOf(events);
    }
}
