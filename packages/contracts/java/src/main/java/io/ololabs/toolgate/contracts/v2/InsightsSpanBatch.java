// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Spans from one producer. Best-effort: droppedSpans counts what the producer could not keep.
 *
 * @param spans canonical spans value
 * @param droppedSpans canonical droppedSpans value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record InsightsSpanBatch(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "spans", required = true) java.util.List<InsightsSpan> spans,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "droppedSpans", required = false) Long droppedSpans
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param spans canonical spans value
     * @param droppedSpans canonical droppedSpans value
     */
    public InsightsSpanBatch {
        java.util.Objects.requireNonNull(spans, "spans");
        spans = spans == null ? null : java.util.List.copyOf(spans);
    }
}
