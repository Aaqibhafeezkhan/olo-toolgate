// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Durable admission evidence bound to this subscription UID and delivered cursor. ADMITTED requires runId; DUPLICATE returns its original durable outcome reference and runId when one exists. Terminal rejection/filtering is recorded, never inferred locally.
 *
 * @param subscriptionUid canonical subscriptionUid value
 * @param cursor canonical cursor value
 * @param admissionId canonical admissionId value
 * @param disposition canonical disposition value
 * @param runId canonical runId value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowDispatchReceipt(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "subscriptionUid", required = true) String subscriptionUid,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "cursor", required = true) String cursor,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "admissionId", required = true) String admissionId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "disposition", required = true) WorkflowDispatchDisposition disposition,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "runId", required = false) String runId
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param subscriptionUid canonical subscriptionUid value
     * @param cursor canonical cursor value
     * @param admissionId canonical admissionId value
     * @param disposition canonical disposition value
     * @param runId canonical runId value
     */
    public WorkflowDispatchReceipt {
        java.util.Objects.requireNonNull(subscriptionUid, "subscriptionUid");
        java.util.Objects.requireNonNull(cursor, "cursor");
        java.util.Objects.requireNonNull(admissionId, "admissionId");
        java.util.Objects.requireNonNull(disposition, "disposition");
    }
}
