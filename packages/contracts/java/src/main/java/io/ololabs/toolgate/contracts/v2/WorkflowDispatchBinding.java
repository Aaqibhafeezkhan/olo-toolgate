// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Profile declaration for WorkflowDispatchBinding; eligibility and configuration confer no runtime authority.
 *
 * @param id canonical id value
 * @param subscriptionUid canonical subscriptionUid value
 * @param sourceAssignmentUid canonical sourceAssignmentUid value
 * @param workflowId canonical workflowId value
 * @param workflowVersionId canonical workflowVersionId value
 * @param serviceAgentId canonical serviceAgentId value
 * @param serviceAgentGroupId canonical serviceAgentGroupId value
 * @param messagePointer canonical messagePointer value
 * @param remediationMode canonical remediationMode value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowDispatchBinding(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "id", required = true) String id,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "subscriptionUid", required = true) String subscriptionUid,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "sourceAssignmentUid", required = true) String sourceAssignmentUid,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "workflowId", required = true) String workflowId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "workflowVersionId", required = true) String workflowVersionId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "serviceAgentId", required = true) String serviceAgentId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "serviceAgentGroupId", required = true) String serviceAgentGroupId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "messagePointer", required = true) String messagePointer,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "remediationMode", required = true) WorkflowRemediationMode remediationMode
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param id canonical id value
     * @param subscriptionUid canonical subscriptionUid value
     * @param sourceAssignmentUid canonical sourceAssignmentUid value
     * @param workflowId canonical workflowId value
     * @param workflowVersionId canonical workflowVersionId value
     * @param serviceAgentId canonical serviceAgentId value
     * @param serviceAgentGroupId canonical serviceAgentGroupId value
     * @param messagePointer canonical messagePointer value
     * @param remediationMode canonical remediationMode value
     */
    public WorkflowDispatchBinding {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(subscriptionUid, "subscriptionUid");
        java.util.Objects.requireNonNull(sourceAssignmentUid, "sourceAssignmentUid");
        java.util.Objects.requireNonNull(workflowId, "workflowId");
        java.util.Objects.requireNonNull(workflowVersionId, "workflowVersionId");
        java.util.Objects.requireNonNull(serviceAgentId, "serviceAgentId");
        java.util.Objects.requireNonNull(serviceAgentGroupId, "serviceAgentGroupId");
        java.util.Objects.requireNonNull(messagePointer, "messagePointer");
        java.util.Objects.requireNonNull(remediationMode, "remediationMode");
    }
}
