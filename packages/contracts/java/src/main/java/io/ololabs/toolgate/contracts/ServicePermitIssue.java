// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Executor request for downstream authorization; scope comes exclusively from the admitted invocation.
 *
 * @param invocationId canonical invocationId value
 * @param fencingToken canonical fencingToken value
 * @param audience canonical audience value
 * @param httpRequestDigest canonical httpRequestDigest value
 * @param httpRequestManifest canonical httpRequestManifest value
 * @param invocationPermit canonical invocationPermit value
 * @param brokerInstanceId canonical brokerInstanceId value
 * @param handlerId canonical handlerId value
 * @param effectKey canonical effectKey value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ServicePermitIssue(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "invocationId", required = true) String invocationId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "fencingToken", required = true) String fencingToken,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "audience", required = true) ServiceAudience audience,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "httpRequestDigest", required = true) String httpRequestDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "httpRequestManifest", required = true) PreparedHttpRequestManifest httpRequestManifest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "invocationPermit", required = true) io.ololabs.toolgate.contracts.v2.SignedInvocationPermit invocationPermit,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "brokerInstanceId", required = true) String brokerInstanceId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "handlerId", required = true) String handlerId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "effectKey", required = false) String effectKey
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param invocationId canonical invocationId value
     * @param fencingToken canonical fencingToken value
     * @param audience canonical audience value
     * @param httpRequestDigest canonical httpRequestDigest value
     * @param httpRequestManifest canonical httpRequestManifest value
     * @param invocationPermit canonical invocationPermit value
     * @param brokerInstanceId canonical brokerInstanceId value
     * @param handlerId canonical handlerId value
     * @param effectKey canonical effectKey value
     */
    public ServicePermitIssue {
        java.util.Objects.requireNonNull(invocationId, "invocationId");
        java.util.Objects.requireNonNull(fencingToken, "fencingToken");
        java.util.Objects.requireNonNull(audience, "audience");
        java.util.Objects.requireNonNull(httpRequestDigest, "httpRequestDigest");
        java.util.Objects.requireNonNull(httpRequestManifest, "httpRequestManifest");
        java.util.Objects.requireNonNull(invocationPermit, "invocationPermit");
        java.util.Objects.requireNonNull(brokerInstanceId, "brokerInstanceId");
        java.util.Objects.requireNonNull(handlerId, "handlerId");
    }
}
