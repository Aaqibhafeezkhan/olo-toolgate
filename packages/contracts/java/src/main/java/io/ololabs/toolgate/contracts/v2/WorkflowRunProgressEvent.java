// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Backend-recorded structural progress. Runtime reports do not attest tool effects or finality.
 *
 * @param sequence canonical sequence value
 * @param occurredAt canonical occurredAt value
 * @param source canonical source value
 * @param kind canonical kind value
 * @param nodeId canonical nodeId value
 * @param subjectId canonical subjectId value
 * @param toolRef canonical toolRef value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowRunProgressEvent(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "sequence", required = true) Long sequence,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "occurredAt", required = true) String occurredAt,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "source", required = true) WorkflowProgressSource source,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "kind", required = true) String kind,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "nodeId", required = false) String nodeId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "subjectId", required = false) String subjectId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toolRef", required = false) String toolRef
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param sequence canonical sequence value
     * @param occurredAt canonical occurredAt value
     * @param source canonical source value
     * @param kind canonical kind value
     * @param nodeId canonical nodeId value
     * @param subjectId canonical subjectId value
     * @param toolRef canonical toolRef value
     */
    public WorkflowRunProgressEvent {
        java.util.Objects.requireNonNull(sequence, "sequence");
        java.util.Objects.requireNonNull(occurredAt, "occurredAt");
        java.util.Objects.requireNonNull(source, "source");
        java.util.Objects.requireNonNull(kind, "kind");
    }
}
