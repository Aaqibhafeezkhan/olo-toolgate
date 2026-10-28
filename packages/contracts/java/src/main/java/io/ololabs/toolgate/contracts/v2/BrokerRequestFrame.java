// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Tool to executor on stdout: one brokered operation. request is the operation payload: the recorded-exchange route call shape for ROUTE_CALL, {handle} for SECRET_REDEEM, {path, destination, text} for FILES_*. One request is outstanding at a time.
 *
 * @param kind canonical kind value
 * @param requestId canonical requestId value
 * @param operation canonical operation value
 * @param request canonical request value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record BrokerRequestFrame(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "kind", required = true) String kind,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "requestId", required = true) String requestId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "operation", required = true) BrokerOperation operation,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "request", required = true) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> request
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param kind canonical kind value
     * @param requestId canonical requestId value
     * @param operation canonical operation value
     * @param request canonical request value
     */
    public BrokerRequestFrame {
        java.util.Objects.requireNonNull(kind, "kind");
        if (kind != null && !java.util.Objects.equals(kind, "BROKER_REQUEST")) throw new IllegalArgumentException("kind must be BROKER_REQUEST");
        java.util.Objects.requireNonNull(requestId, "requestId");
        java.util.Objects.requireNonNull(operation, "operation");
        java.util.Objects.requireNonNull(request, "request");
        request = request == null ? null : java.util.Map.copyOf(request);
    }
}
