// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Bounded lease delivered only over device-authenticated polling; executable definitions are never accepted from an agent.
 *
 * @param requestId canonical requestId value
 * @param leaseId canonical leaseId value
 * @param request canonical request value
 * @param expiresAtUnixMs canonical expiresAtUnixMs value
 * @param invocation canonical invocation value
 * @param permit canonical permit value
 * @param traceparent canonical traceparent value
 * @param executionContext canonical executionContext value
 * @param collectSpans canonical collectSpans value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record RemoteToolTask(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "requestId", required = true) String requestId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "leaseId", required = true) String leaseId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "request", required = true) AuthorizationRequest request,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "expiresAtUnixMs", required = true) Long expiresAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "invocation", required = true) EnterpriseInvocation invocation,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "permit", required = true) EnterpriseSignedPermit permit,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "traceparent", required = false) String traceparent,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "executionContext", required = false) io.ololabs.toolgate.contracts.v2.ExecutionContext executionContext,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "collectSpans", required = false) Boolean collectSpans
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param requestId canonical requestId value
     * @param leaseId canonical leaseId value
     * @param request canonical request value
     * @param expiresAtUnixMs canonical expiresAtUnixMs value
     * @param invocation canonical invocation value
     * @param permit canonical permit value
     * @param traceparent canonical traceparent value
     * @param executionContext canonical executionContext value
     * @param collectSpans canonical collectSpans value
     */
    public RemoteToolTask {
        java.util.Objects.requireNonNull(requestId, "requestId");
        java.util.Objects.requireNonNull(leaseId, "leaseId");
        java.util.Objects.requireNonNull(request, "request");
        java.util.Objects.requireNonNull(expiresAtUnixMs, "expiresAtUnixMs");
        java.util.Objects.requireNonNull(invocation, "invocation");
        java.util.Objects.requireNonNull(permit, "permit");
    }
}
