// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Single-owner exact-request authorization. Target consumption is the ordering point for explicitly fenced HTTP targets; unfenced dispatch remains bounded to two seconds.
 *
 * @param iss canonical iss value
 * @param aud canonical aud value
 * @param iat canonical iat value
 * @param nbf canonical nbf value
 * @param exp canonical exp value
 * @param tenantId canonical tenantId value
 * @param invocationId canonical invocationId value
 * @param fencingToken canonical fencingToken value
 * @param authorizationEpoch canonical authorizationEpoch value
 * @param effectId canonical effectId value
 * @param generation canonical generation value
 * @param authNonce canonical authNonce value
 * @param brokerInstanceId canonical brokerInstanceId value
 * @param handlerId canonical handlerId value
 * @param httpRequestDigest canonical httpRequestDigest value
 * @param profileDigest canonical profileDigest value
 * @param routeId canonical routeId value
 * @param classification canonical classification value
 * @param downstreamKey canonical downstreamKey value
 * @param fencedTarget canonical fencedTarget value
 * @param executorKeySha256 canonical executorKeySha256 value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record EffectGrantClaims(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "iss", required = true) String iss,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "aud", required = true) String aud,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "iat", required = true) Long iat,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "nbf", required = true) Long nbf,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "exp", required = true) Long exp,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tenantId", required = true) String tenantId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "invocationId", required = true) String invocationId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "fencingToken", required = true) String fencingToken,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "authorizationEpoch", required = true) Long authorizationEpoch,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "effectId", required = true) String effectId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "generation", required = true) Long generation,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "authNonce", required = true) String authNonce,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "brokerInstanceId", required = true) String brokerInstanceId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "handlerId", required = true) String handlerId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "httpRequestDigest", required = true) String httpRequestDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "profileDigest", required = true) String profileDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "routeId", required = true) String routeId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "classification", required = true) EffectClassification classification,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "downstreamKey", required = false) DownstreamEffectKey downstreamKey,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "fencedTarget", required = false) FencedTarget fencedTarget,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "executorKeySha256", required = true) String executorKeySha256
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param iss canonical iss value
     * @param aud canonical aud value
     * @param iat canonical iat value
     * @param nbf canonical nbf value
     * @param exp canonical exp value
     * @param tenantId canonical tenantId value
     * @param invocationId canonical invocationId value
     * @param fencingToken canonical fencingToken value
     * @param authorizationEpoch canonical authorizationEpoch value
     * @param effectId canonical effectId value
     * @param generation canonical generation value
     * @param authNonce canonical authNonce value
     * @param brokerInstanceId canonical brokerInstanceId value
     * @param handlerId canonical handlerId value
     * @param httpRequestDigest canonical httpRequestDigest value
     * @param profileDigest canonical profileDigest value
     * @param routeId canonical routeId value
     * @param classification canonical classification value
     * @param downstreamKey canonical downstreamKey value
     * @param fencedTarget canonical fencedTarget value
     * @param executorKeySha256 canonical executorKeySha256 value
     */
    public EffectGrantClaims {
        java.util.Objects.requireNonNull(iss, "iss");
        java.util.Objects.requireNonNull(aud, "aud");
        java.util.Objects.requireNonNull(iat, "iat");
        java.util.Objects.requireNonNull(nbf, "nbf");
        java.util.Objects.requireNonNull(exp, "exp");
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        java.util.Objects.requireNonNull(invocationId, "invocationId");
        java.util.Objects.requireNonNull(fencingToken, "fencingToken");
        java.util.Objects.requireNonNull(authorizationEpoch, "authorizationEpoch");
        java.util.Objects.requireNonNull(effectId, "effectId");
        java.util.Objects.requireNonNull(generation, "generation");
        java.util.Objects.requireNonNull(authNonce, "authNonce");
        java.util.Objects.requireNonNull(brokerInstanceId, "brokerInstanceId");
        java.util.Objects.requireNonNull(handlerId, "handlerId");
        java.util.Objects.requireNonNull(httpRequestDigest, "httpRequestDigest");
        java.util.Objects.requireNonNull(profileDigest, "profileDigest");
        java.util.Objects.requireNonNull(routeId, "routeId");
        java.util.Objects.requireNonNull(classification, "classification");
        java.util.Objects.requireNonNull(executorKeySha256, "executorKeySha256");
    }
}
