// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Trusted complete runtime identity. Authentication adapters construct it; ordinary arguments cannot change it. deviceId is the device Control placed the call on: the one online device of the execution binding device group (the user's own first), or the device a human chose.
 *
 * @param requestId canonical requestId value
 * @param tenantId canonical tenantId value
 * @param mode canonical mode value
 * @param userId canonical userId value
 * @param agentId canonical agentId value
 * @param workloadBindingId canonical workloadBindingId value
 * @param chain canonical chain value
 * @param sessionEpoch canonical sessionEpoch value
 * @param credentialEpoch canonical credentialEpoch value
 * @param bindingId canonical bindingId value
 * @param deviceId canonical deviceId value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record EnterpriseContext(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "requestId", required = true) String requestId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tenantId", required = true) String tenantId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "mode", required = true) EnterpriseRequestMode mode,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "userId", required = false) String userId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "agentId", required = false) String agentId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "workloadBindingId", required = false) String workloadBindingId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "chain", required = true) java.util.List<EnterpriseActorHop> chain,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "sessionEpoch", required = false) Long sessionEpoch,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "credentialEpoch", required = false) Long credentialEpoch,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "bindingId", required = true) String bindingId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deviceId", required = true) String deviceId
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param requestId canonical requestId value
     * @param tenantId canonical tenantId value
     * @param mode canonical mode value
     * @param userId canonical userId value
     * @param agentId canonical agentId value
     * @param workloadBindingId canonical workloadBindingId value
     * @param chain canonical chain value
     * @param sessionEpoch canonical sessionEpoch value
     * @param credentialEpoch canonical credentialEpoch value
     * @param bindingId canonical bindingId value
     * @param deviceId canonical deviceId value
     */
    public EnterpriseContext {
        java.util.Objects.requireNonNull(requestId, "requestId");
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        java.util.Objects.requireNonNull(mode, "mode");
        java.util.Objects.requireNonNull(chain, "chain");
        chain = chain == null ? null : java.util.List.copyOf(chain);
        java.util.Objects.requireNonNull(bindingId, "bindingId");
        java.util.Objects.requireNonNull(deviceId, "deviceId");
    }
}
