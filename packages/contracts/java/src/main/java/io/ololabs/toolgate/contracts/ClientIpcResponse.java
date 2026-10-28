// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Endpoint identity foundation wire model.
 *
 * @param requestId canonical requestId value
 * @param health canonical health value
 * @param challenge canonical challenge value
 * @param error canonical error value
 * @param activity canonical activity value
 * @param connections canonical connections value
 * @param power canonical power value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ClientIpcResponse(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "requestId", required = true) String requestId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "health", required = false) ClientHealth health,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "challenge", required = false) EndpointEnrollmentPrompt challenge,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "error", required = false) ErrorCode error,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "activity", required = false) ClientActivity activity,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "connections", required = false) java.util.List<ClientGatewayConnection> connections,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "power", required = false) DevicePower power
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param requestId canonical requestId value
     * @param health canonical health value
     * @param challenge canonical challenge value
     * @param error canonical error value
     * @param activity canonical activity value
     * @param connections canonical connections value
     * @param power canonical power value
     */
    public ClientIpcResponse {
        java.util.Objects.requireNonNull(requestId, "requestId");
        connections = connections == null ? null : java.util.List.copyOf(connections);
    }
}
