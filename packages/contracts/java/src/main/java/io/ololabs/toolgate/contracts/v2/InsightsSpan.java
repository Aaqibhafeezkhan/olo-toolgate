// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** One allowlisted span. Metadata only: never arguments, outputs, URLs, headers or secrets.
 *
 * @param traceId canonical traceId value
 * @param spanId canonical spanId value
 * @param parentSpanId canonical parentSpanId value
 * @param name canonical name value
 * @param source canonical source value
 * @param invocationId canonical invocationId value
 * @param executionId canonical executionId value
 * @param startUnixMs canonical startUnixMs value
 * @param durationMs canonical durationMs value
 * @param status canonical status value
 * @param routeId canonical routeId value
 * @param httpStatus canonical httpStatus value
 * @param errorCode canonical errorCode value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record InsightsSpan(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "traceId", required = true) String traceId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "spanId", required = true) String spanId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "parentSpanId", required = false) String parentSpanId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "name", required = true) InsightsSpanName name,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "source", required = false) InsightsSpanSource source,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "invocationId", required = true) String invocationId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "executionId", required = false) String executionId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "startUnixMs", required = true) Long startUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "durationMs", required = true) Long durationMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "status", required = true) InsightsSpanStatus status,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "routeId", required = false) String routeId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "httpStatus", required = false) Long httpStatus,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "errorCode", required = false) String errorCode
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param traceId canonical traceId value
     * @param spanId canonical spanId value
     * @param parentSpanId canonical parentSpanId value
     * @param name canonical name value
     * @param source canonical source value
     * @param invocationId canonical invocationId value
     * @param executionId canonical executionId value
     * @param startUnixMs canonical startUnixMs value
     * @param durationMs canonical durationMs value
     * @param status canonical status value
     * @param routeId canonical routeId value
     * @param httpStatus canonical httpStatus value
     * @param errorCode canonical errorCode value
     */
    public InsightsSpan {
        java.util.Objects.requireNonNull(traceId, "traceId");
        java.util.Objects.requireNonNull(spanId, "spanId");
        java.util.Objects.requireNonNull(name, "name");
        java.util.Objects.requireNonNull(invocationId, "invocationId");
        java.util.Objects.requireNonNull(startUnixMs, "startUnixMs");
        java.util.Objects.requireNonNull(durationMs, "durationMs");
        java.util.Objects.requireNonNull(status, "status");
    }
}
