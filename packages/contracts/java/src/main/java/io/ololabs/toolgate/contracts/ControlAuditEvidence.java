// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Authorization facts of an invocation event as separate fields (OLO M4.4). Never arguments, payloads or credentials.
 *
 * @param invocationId canonical invocationId value
 * @param mode canonical mode value
 * @param subjectUserId canonical subjectUserId value
 * @param actorAgentId canonical actorAgentId value
 * @param delegationChain canonical delegationChain value
 * @param workloadBindingId canonical workloadBindingId value
 * @param credentialKind canonical credentialKind value
 * @param toolId canonical toolId value
 * @param operation canonical operation value
 * @param resources canonical resources value
 * @param resourceCount canonical resourceCount value
 * @param resourcesDigest canonical resourcesDigest value
 * @param decision canonical decision value
 * @param state canonical state value
 * @param authorizationEpoch canonical authorizationEpoch value
 * @param policyRevision canonical policyRevision value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ControlAuditEvidence(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "invocationId", required = true) String invocationId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "mode", required = true) String mode,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "subjectUserId", required = false) String subjectUserId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "actorAgentId", required = false) String actorAgentId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "delegationChain", required = false) java.util.List<String> delegationChain,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "workloadBindingId", required = false) String workloadBindingId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "credentialKind", required = false) String credentialKind,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toolId", required = true) String toolId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "operation", required = true) String operation,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "resources", required = false) java.util.List<String> resources,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "resourceCount", required = true) Long resourceCount,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "resourcesDigest", required = true) String resourcesDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "decision", required = false) Decision decision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "state", required = true) String state,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "authorizationEpoch", required = true) Long authorizationEpoch,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "policyRevision", required = true) Long policyRevision
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param invocationId canonical invocationId value
     * @param mode canonical mode value
     * @param subjectUserId canonical subjectUserId value
     * @param actorAgentId canonical actorAgentId value
     * @param delegationChain canonical delegationChain value
     * @param workloadBindingId canonical workloadBindingId value
     * @param credentialKind canonical credentialKind value
     * @param toolId canonical toolId value
     * @param operation canonical operation value
     * @param resources canonical resources value
     * @param resourceCount canonical resourceCount value
     * @param resourcesDigest canonical resourcesDigest value
     * @param decision canonical decision value
     * @param state canonical state value
     * @param authorizationEpoch canonical authorizationEpoch value
     * @param policyRevision canonical policyRevision value
     */
    public ControlAuditEvidence {
        java.util.Objects.requireNonNull(invocationId, "invocationId");
        java.util.Objects.requireNonNull(mode, "mode");
        delegationChain = delegationChain == null ? null : java.util.List.copyOf(delegationChain);
        java.util.Objects.requireNonNull(toolId, "toolId");
        java.util.Objects.requireNonNull(operation, "operation");
        resources = resources == null ? null : java.util.List.copyOf(resources);
        java.util.Objects.requireNonNull(resourceCount, "resourceCount");
        java.util.Objects.requireNonNull(resourcesDigest, "resourcesDigest");
        java.util.Objects.requireNonNull(state, "state");
        java.util.Objects.requireNonNull(authorizationEpoch, "authorizationEpoch");
        java.util.Objects.requireNonNull(policyRevision, "policyRevision");
    }
}
