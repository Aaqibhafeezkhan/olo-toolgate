// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Dedicated Gateway-authenticated request for one device-local tool.
 *
 * @param request canonical request value
 * @param expiresAtUnixMs canonical expiresAtUnixMs value
 * @param context canonical context value
 * @param invocationId canonical invocationId value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record RemoteToolSubmission(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "request", required = true) AuthorizationRequest request,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "expiresAtUnixMs", required = true) Long expiresAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "context", required = true) io.ololabs.toolgate.contracts.v2.RequestContext context,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "invocationId", required = true) String invocationId
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param request canonical request value
     * @param expiresAtUnixMs canonical expiresAtUnixMs value
     * @param context canonical context value
     * @param invocationId canonical invocationId value
     */
    public RemoteToolSubmission {
        java.util.Objects.requireNonNull(request, "request");
        java.util.Objects.requireNonNull(expiresAtUnixMs, "expiresAtUnixMs");
        java.util.Objects.requireNonNull(context, "context");
        java.util.Objects.requireNonNull(invocationId, "invocationId");
    }
}
