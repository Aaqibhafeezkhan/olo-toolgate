// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Permit claims. exp is the short admission window, not the execution deadline.
 *
 * @param jti canonical jti value
 * @param iss canonical iss value
 * @param aud canonical aud value
 * @param iat canonical iat value
 * @param nbf canonical nbf value
 * @param exp canonical exp value
 * @param nonce canonical nonce value
 * @param invocationId canonical invocationId value
 * @param attempt canonical attempt value
 * @param taskId canonical taskId value
 * @param identity canonical identity value
 * @param target canonical target value
 * @param catalogRevision canonical catalogRevision value
 * @param argumentsDigest canonical argumentsDigest value
 * @param action canonical action value
 * @param resources canonical resources value
 * @param effectSafety canonical effectSafety value
 * @param requiresExecutionLedger canonical requiresExecutionLedger value
 * @param businessKeyHash canonical businessKeyHash value
 * @param businessKeyNamespace canonical businessKeyNamespace value
 * @param approvalIds canonical approvalIds value
 * @param deadlineUnixMs canonical deadlineUnixMs value
 * @param mcpVerification canonical mcpVerification value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record InvocationPermitClaims(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "jti", required = true) String jti,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "iss", required = true) String iss,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "aud", required = true) String aud,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "iat", required = true) Long iat,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "nbf", required = true) Long nbf,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "exp", required = true) Long exp,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "nonce", required = true) String nonce,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "invocationId", required = true) String invocationId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "attempt", required = true) Long attempt,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "taskId", required = false) String taskId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "identity", required = true) PermitIdentity identity,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "target", required = true) PermitTarget target,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "catalogRevision", required = true) String catalogRevision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "argumentsDigest", required = true) String argumentsDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "action", required = true) String action,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "resources", required = true) java.util.List<java.util.Map<String, com.fasterxml.jackson.databind.JsonNode>> resources,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "effectSafety", required = true) EffectSafety effectSafety,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "requiresExecutionLedger", required = true) Boolean requiresExecutionLedger,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "businessKeyHash", required = false) String businessKeyHash,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "businessKeyNamespace", required = false) KeyNamespace businessKeyNamespace,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "approvalIds", required = true) java.util.List<String> approvalIds,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deadlineUnixMs", required = true) Long deadlineUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "mcpVerification", required = false) McpPermitVerification mcpVerification
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param jti canonical jti value
     * @param iss canonical iss value
     * @param aud canonical aud value
     * @param iat canonical iat value
     * @param nbf canonical nbf value
     * @param exp canonical exp value
     * @param nonce canonical nonce value
     * @param invocationId canonical invocationId value
     * @param attempt canonical attempt value
     * @param taskId canonical taskId value
     * @param identity canonical identity value
     * @param target canonical target value
     * @param catalogRevision canonical catalogRevision value
     * @param argumentsDigest canonical argumentsDigest value
     * @param action canonical action value
     * @param resources canonical resources value
     * @param effectSafety canonical effectSafety value
     * @param requiresExecutionLedger canonical requiresExecutionLedger value
     * @param businessKeyHash canonical businessKeyHash value
     * @param businessKeyNamespace canonical businessKeyNamespace value
     * @param approvalIds canonical approvalIds value
     * @param deadlineUnixMs canonical deadlineUnixMs value
     * @param mcpVerification canonical mcpVerification value
     */
    public InvocationPermitClaims {
        java.util.Objects.requireNonNull(jti, "jti");
        java.util.Objects.requireNonNull(iss, "iss");
        java.util.Objects.requireNonNull(aud, "aud");
        java.util.Objects.requireNonNull(iat, "iat");
        java.util.Objects.requireNonNull(nbf, "nbf");
        java.util.Objects.requireNonNull(exp, "exp");
        java.util.Objects.requireNonNull(nonce, "nonce");
        java.util.Objects.requireNonNull(invocationId, "invocationId");
        java.util.Objects.requireNonNull(attempt, "attempt");
        java.util.Objects.requireNonNull(identity, "identity");
        java.util.Objects.requireNonNull(target, "target");
        java.util.Objects.requireNonNull(catalogRevision, "catalogRevision");
        java.util.Objects.requireNonNull(argumentsDigest, "argumentsDigest");
        java.util.Objects.requireNonNull(action, "action");
        java.util.Objects.requireNonNull(resources, "resources");
        resources = resources == null ? null : java.util.List.copyOf(resources);
        java.util.Objects.requireNonNull(effectSafety, "effectSafety");
        java.util.Objects.requireNonNull(requiresExecutionLedger, "requiresExecutionLedger");
        java.util.Objects.requireNonNull(approvalIds, "approvalIds");
        approvalIds = approvalIds == null ? null : java.util.List.copyOf(approvalIds);
        java.util.Objects.requireNonNull(deadlineUnixMs, "deadlineUnixMs");
    }
}
