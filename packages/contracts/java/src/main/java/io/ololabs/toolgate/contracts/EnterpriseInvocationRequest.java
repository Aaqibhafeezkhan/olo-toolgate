// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Exact authenticated invocation with original arguments and installed code digests.
 *
 * @param context canonical context value
 * @param request canonical request value
 * @param toolDigest canonical toolDigest value
 * @param packageDigest canonical packageDigest value
 * @param downstreamIdempotencyKey canonical downstreamIdempotencyKey value
 * @param executionContext canonical executionContext value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record EnterpriseInvocationRequest(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "context", required = true) io.ololabs.toolgate.contracts.v2.RequestContext context,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "request", required = true) AuthorizationRequest request,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toolDigest", required = true) String toolDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageDigest", required = true) String packageDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "downstreamIdempotencyKey", required = false) String downstreamIdempotencyKey,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "executionContext", required = false) io.ololabs.toolgate.contracts.v2.ExecutionContext executionContext
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param context canonical context value
     * @param request canonical request value
     * @param toolDigest canonical toolDigest value
     * @param packageDigest canonical packageDigest value
     * @param downstreamIdempotencyKey canonical downstreamIdempotencyKey value
     * @param executionContext canonical executionContext value
     */
    public EnterpriseInvocationRequest {
        java.util.Objects.requireNonNull(context, "context");
        java.util.Objects.requireNonNull(request, "request");
        java.util.Objects.requireNonNull(toolDigest, "toolDigest");
        java.util.Objects.requireNonNull(packageDigest, "packageDigest");
    }
}
