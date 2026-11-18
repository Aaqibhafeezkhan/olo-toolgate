// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Lease-authenticated structural runtime observation. No input, output, provider text or credential is retained in progress evidence.
 *
 * @param leaseId canonical leaseId value
 * @param leaseToken canonical leaseToken value
 * @param eventId canonical eventId value
 * @param kind canonical kind value
 * @param nodeId canonical nodeId value
 * @param subjectId canonical subjectId value
 * @param toolRef canonical toolRef value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowRunProgressRequest(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "leaseId", required = true) String leaseId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "leaseToken", required = true) String leaseToken,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "eventId", required = true) String eventId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "kind", required = true) WorkflowProgressKind kind,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "nodeId", required = false) String nodeId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "subjectId", required = false) String subjectId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toolRef", required = false) String toolRef
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param leaseId canonical leaseId value
     * @param leaseToken canonical leaseToken value
     * @param eventId canonical eventId value
     * @param kind canonical kind value
     * @param nodeId canonical nodeId value
     * @param subjectId canonical subjectId value
     * @param toolRef canonical toolRef value
     */
    public WorkflowRunProgressRequest {
        java.util.Objects.requireNonNull(leaseId, "leaseId");
        java.util.Objects.requireNonNull(leaseToken, "leaseToken");
        java.util.Objects.requireNonNull(eventId, "eventId");
        java.util.Objects.requireNonNull(kind, "kind");
    }
}
