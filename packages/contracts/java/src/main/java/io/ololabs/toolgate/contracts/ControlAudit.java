// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Append-only mutation metadata; payloads and credentials are excluded.
 *
 * @param sequence canonical sequence value
 * @param tenantId canonical tenantId value
 * @param actorId canonical actorId value
 * @param operation canonical operation value
 * @param target canonical target value
 * @param revision canonical revision value
 * @param requestId canonical requestId value
 * @param occurredAt canonical occurredAt value
 * @param requestDigest canonical requestDigest value
 * @param evidence canonical evidence value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ControlAudit(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "sequence", required = true) Long sequence,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tenantId", required = true) String tenantId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "actorId", required = true) String actorId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "operation", required = true) String operation,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "target", required = true) String target,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "revision", required = true) Long revision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "requestId", required = true) String requestId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "occurredAt", required = true) String occurredAt,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "requestDigest", required = true) String requestDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "evidence", required = false) ControlAuditEvidence evidence
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param sequence canonical sequence value
     * @param tenantId canonical tenantId value
     * @param actorId canonical actorId value
     * @param operation canonical operation value
     * @param target canonical target value
     * @param revision canonical revision value
     * @param requestId canonical requestId value
     * @param occurredAt canonical occurredAt value
     * @param requestDigest canonical requestDigest value
     * @param evidence canonical evidence value
     */
    public ControlAudit {
        java.util.Objects.requireNonNull(sequence, "sequence");
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        java.util.Objects.requireNonNull(actorId, "actorId");
        java.util.Objects.requireNonNull(operation, "operation");
        java.util.Objects.requireNonNull(target, "target");
        java.util.Objects.requireNonNull(revision, "revision");
        java.util.Objects.requireNonNull(requestId, "requestId");
        java.util.Objects.requireNonNull(occurredAt, "occurredAt");
        java.util.Objects.requireNonNull(requestDigest, "requestDigest");
    }
}
