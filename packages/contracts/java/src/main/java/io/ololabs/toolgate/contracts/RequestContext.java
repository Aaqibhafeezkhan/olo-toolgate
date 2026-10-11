// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Authenticated Gateway runtime context. Carries no device: Control places a client tool call on a device from the execution binding device group (ADR 015 amendment), and server-side agents have none.
 *
 * @param requestId canonical requestId value
 * @param tenantId canonical tenantId value
 * @param mode canonical mode value
 * @param credentialKind canonical credentialKind value
 * @param issuer canonical issuer value
 * @param userId canonical userId value
 * @param agentId canonical agentId value
 * @param workloadBindingId canonical workloadBindingId value
 * @param chain canonical chain value
 * @param sessionEpoch canonical sessionEpoch value
 * @param credentialEpoch canonical credentialEpoch value
 * @param delegatedSessionEpoch canonical delegatedSessionEpoch value
 * @param expiresAtUnixMs canonical expiresAtUnixMs value
 * @param bindingId canonical bindingId value
 * @param credentialSha256 canonical credentialSha256 value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record RequestContext(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "requestId", required = true) String requestId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tenantId", required = true) String tenantId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "mode", required = true) EnterpriseRequestMode mode,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "credentialKind", required = true) CredentialKind credentialKind,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "issuer", required = false) String issuer,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "userId", required = false) String userId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "agentId", required = false) String agentId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "workloadBindingId", required = false) String workloadBindingId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "chain", required = true) java.util.List<EnterpriseActorHop> chain,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "sessionEpoch", required = false) Long sessionEpoch,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "credentialEpoch", required = false) Long credentialEpoch,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "delegatedSessionEpoch", required = false) Long delegatedSessionEpoch,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "expiresAtUnixMs", required = false) Long expiresAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "bindingId", required = false) String bindingId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "credentialSha256", required = false) String credentialSha256
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param requestId canonical requestId value
     * @param tenantId canonical tenantId value
     * @param mode canonical mode value
     * @param credentialKind canonical credentialKind value
     * @param issuer canonical issuer value
     * @param userId canonical userId value
     * @param agentId canonical agentId value
     * @param workloadBindingId canonical workloadBindingId value
     * @param chain canonical chain value
     * @param sessionEpoch canonical sessionEpoch value
     * @param credentialEpoch canonical credentialEpoch value
     * @param delegatedSessionEpoch canonical delegatedSessionEpoch value
     * @param expiresAtUnixMs canonical expiresAtUnixMs value
     * @param bindingId canonical bindingId value
     * @param credentialSha256 canonical credentialSha256 value
     */
    public RequestContext {
        java.util.Objects.requireNonNull(requestId, "requestId");
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        java.util.Objects.requireNonNull(mode, "mode");
        java.util.Objects.requireNonNull(credentialKind, "credentialKind");
        java.util.Objects.requireNonNull(chain, "chain");
        chain = chain == null ? null : java.util.List.copyOf(chain);
    }
}
