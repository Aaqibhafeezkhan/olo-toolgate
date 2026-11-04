// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Request for fresh owner-bound authority. Control independently verifies the signed argument preimage and reconstructs the reviewed request.
 *
 * @param invocationId canonical invocationId value
 * @param fencingToken canonical fencingToken value
 * @param invocationPermit canonical invocationPermit value
 * @param brokerInstanceId canonical brokerInstanceId value
 * @param handlerId canonical handlerId value
 * @param arguments canonical arguments value
 * @param httpRequestDigest canonical httpRequestDigest value
 * @param httpRequestManifest canonical httpRequestManifest value
 * @param effectKey canonical effectKey value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record EffectGrantIssue(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "invocationId", required = true) String invocationId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "fencingToken", required = true) String fencingToken,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "invocationPermit", required = true) SignedInvocationPermit invocationPermit,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "brokerInstanceId", required = true) String brokerInstanceId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "handlerId", required = true) String handlerId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "arguments", required = true) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> arguments,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "httpRequestDigest", required = true) String httpRequestDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "httpRequestManifest", required = true) io.ololabs.toolgate.contracts.PreparedHttpRequestManifest httpRequestManifest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "effectKey", required = false) String effectKey
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param invocationId canonical invocationId value
     * @param fencingToken canonical fencingToken value
     * @param invocationPermit canonical invocationPermit value
     * @param brokerInstanceId canonical brokerInstanceId value
     * @param handlerId canonical handlerId value
     * @param arguments canonical arguments value
     * @param httpRequestDigest canonical httpRequestDigest value
     * @param httpRequestManifest canonical httpRequestManifest value
     * @param effectKey canonical effectKey value
     */
    public EffectGrantIssue {
        java.util.Objects.requireNonNull(invocationId, "invocationId");
        java.util.Objects.requireNonNull(fencingToken, "fencingToken");
        java.util.Objects.requireNonNull(invocationPermit, "invocationPermit");
        java.util.Objects.requireNonNull(brokerInstanceId, "brokerInstanceId");
        java.util.Objects.requireNonNull(handlerId, "handlerId");
        java.util.Objects.requireNonNull(arguments, "arguments");
        arguments = arguments == null ? null : java.util.Map.copyOf(arguments);
        java.util.Objects.requireNonNull(httpRequestDigest, "httpRequestDigest");
        java.util.Objects.requireNonNull(httpRequestManifest, "httpRequestManifest");
    }
}
