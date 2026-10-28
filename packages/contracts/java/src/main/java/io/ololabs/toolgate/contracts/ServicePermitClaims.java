// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Control-issued exact-request authorization for a downstream service; times are Unix milliseconds.
 *
 * @param iss canonical iss value
 * @param aud canonical aud value
 * @param tenantId canonical tenantId value
 * @param sub canonical sub value
 * @param act canonical act value
 * @param mode canonical mode value
 * @param operation canonical operation value
 * @param resources canonical resources value
 * @param requestFingerprint canonical requestFingerprint value
 * @param executorKeySha256 canonical executorKeySha256 value
 * @param invocationId canonical invocationId value
 * @param nonce canonical nonce value
 * @param authorizationEpoch canonical authorizationEpoch value
 * @param iat canonical iat value
 * @param exp canonical exp value
 * @param executionNonce canonical executionNonce value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ServicePermitClaims(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "iss", required = true) String iss,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "aud", required = true) ServiceAudience aud,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tenantId", required = true) String tenantId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "sub", required = true) String sub,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "act", required = false) String act,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "mode", required = true) io.ololabs.toolgate.contracts.v2.RequestMode mode,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "operation", required = true) String operation,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "resources", required = true) java.util.List<ResourceDescriptor> resources,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "requestFingerprint", required = true) String requestFingerprint,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "executorKeySha256", required = true) String executorKeySha256,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "invocationId", required = true) String invocationId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "nonce", required = true) String nonce,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "authorizationEpoch", required = true) Long authorizationEpoch,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "iat", required = true) Long iat,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "exp", required = true) Long exp,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "executionNonce", required = true) String executionNonce
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param iss canonical iss value
     * @param aud canonical aud value
     * @param tenantId canonical tenantId value
     * @param sub canonical sub value
     * @param act canonical act value
     * @param mode canonical mode value
     * @param operation canonical operation value
     * @param resources canonical resources value
     * @param requestFingerprint canonical requestFingerprint value
     * @param executorKeySha256 canonical executorKeySha256 value
     * @param invocationId canonical invocationId value
     * @param nonce canonical nonce value
     * @param authorizationEpoch canonical authorizationEpoch value
     * @param iat canonical iat value
     * @param exp canonical exp value
     * @param executionNonce canonical executionNonce value
     */
    public ServicePermitClaims {
        java.util.Objects.requireNonNull(iss, "iss");
        java.util.Objects.requireNonNull(aud, "aud");
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        java.util.Objects.requireNonNull(sub, "sub");
        java.util.Objects.requireNonNull(mode, "mode");
        java.util.Objects.requireNonNull(operation, "operation");
        java.util.Objects.requireNonNull(resources, "resources");
        resources = resources == null ? null : java.util.List.copyOf(resources);
        java.util.Objects.requireNonNull(requestFingerprint, "requestFingerprint");
        java.util.Objects.requireNonNull(executorKeySha256, "executorKeySha256");
        java.util.Objects.requireNonNull(invocationId, "invocationId");
        java.util.Objects.requireNonNull(nonce, "nonce");
        java.util.Objects.requireNonNull(authorizationEpoch, "authorizationEpoch");
        java.util.Objects.requireNonNull(iat, "iat");
        java.util.Objects.requireNonNull(exp, "exp");
        java.util.Objects.requireNonNull(executionNonce, "executionNonce");
    }
}
