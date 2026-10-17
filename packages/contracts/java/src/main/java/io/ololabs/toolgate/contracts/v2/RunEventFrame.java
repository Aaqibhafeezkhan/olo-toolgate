// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** One redacted non-terminal SDK frame (LOG, PROGRESS or INPUT_REQUEST) as the executor forwards it. sequence is monotonic per invocation attempt; the executor never renumbers a frame it resends.
 *
 * @param sequence canonical sequence value
 * @param emittedAtUnixMs canonical emittedAtUnixMs value
 * @param kind canonical kind value
 * @param level canonical level value
 * @param message canonical message value
 * @param fraction canonical fraction value
 * @param requestId canonical requestId value
 * @param request canonical request value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record RunEventFrame(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "sequence", required = true) Long sequence,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "emittedAtUnixMs", required = true) Long emittedAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "kind", required = true) RunEventKind kind,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "level", required = false) LogLevel level,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "message", required = false) String message,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "fraction", required = false) Double fraction,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "requestId", required = false) String requestId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "request", required = false) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> request
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param sequence canonical sequence value
     * @param emittedAtUnixMs canonical emittedAtUnixMs value
     * @param kind canonical kind value
     * @param level canonical level value
     * @param message canonical message value
     * @param fraction canonical fraction value
     * @param requestId canonical requestId value
     * @param request canonical request value
     */
    public RunEventFrame {
        java.util.Objects.requireNonNull(sequence, "sequence");
        java.util.Objects.requireNonNull(emittedAtUnixMs, "emittedAtUnixMs");
        java.util.Objects.requireNonNull(kind, "kind");
        request = request == null ? null : java.util.Map.copyOf(request);
    }
}
