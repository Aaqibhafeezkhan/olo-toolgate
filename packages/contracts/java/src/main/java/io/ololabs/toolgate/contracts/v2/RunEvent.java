// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** One retained run-response event. cursor is assigned by Control and orders replay; timestamps are display data only.
 *
 * @param cursor canonical cursor value
 * @param source canonical source value
 * @param kind canonical kind value
 * @param attempt canonical attempt value
 * @param sequence canonical sequence value
 * @param executorId canonical executorId value
 * @param emittedAtUnixMs canonical emittedAtUnixMs value
 * @param recordedAtUnixMs canonical recordedAtUnixMs value
 * @param state canonical state value
 * @param result canonical result value
 * @param level canonical level value
 * @param message canonical message value
 * @param fraction canonical fraction value
 * @param requestId canonical requestId value
 * @param request canonical request value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record RunEvent(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "cursor", required = true) Long cursor,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "source", required = true) RunEventSource source,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "kind", required = true) RunEventKind kind,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "attempt", required = false) Long attempt,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "sequence", required = false) Long sequence,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "executorId", required = false) String executorId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "emittedAtUnixMs", required = false) Long emittedAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "recordedAtUnixMs", required = true) Long recordedAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "state", required = false) String state,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "result", required = false) ResultFrame result,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "level", required = false) LogLevel level,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "message", required = false) String message,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "fraction", required = false) Double fraction,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "requestId", required = false) String requestId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "request", required = false) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> request
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param cursor canonical cursor value
     * @param source canonical source value
     * @param kind canonical kind value
     * @param attempt canonical attempt value
     * @param sequence canonical sequence value
     * @param executorId canonical executorId value
     * @param emittedAtUnixMs canonical emittedAtUnixMs value
     * @param recordedAtUnixMs canonical recordedAtUnixMs value
     * @param state canonical state value
     * @param result canonical result value
     * @param level canonical level value
     * @param message canonical message value
     * @param fraction canonical fraction value
     * @param requestId canonical requestId value
     * @param request canonical request value
     */
    public RunEvent {
        java.util.Objects.requireNonNull(cursor, "cursor");
        java.util.Objects.requireNonNull(source, "source");
        java.util.Objects.requireNonNull(kind, "kind");
        java.util.Objects.requireNonNull(recordedAtUnixMs, "recordedAtUnixMs");
        request = request == null ? null : java.util.Map.copyOf(request);
    }
}
