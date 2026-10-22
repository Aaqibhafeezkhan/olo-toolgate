// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Requester-scoped device state and optional verified tenant UUID. Errors carry no installation or device metadata. The service enforces the error-only response invariant before serialization.
 *
 * @param requestId canonical requestId value
 * @param tenantId canonical tenantId value
 * @param health canonical health value
 * @param challenge canonical challenge value
 * @param error canonical error value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ConsoleClientResponse(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "requestId", required = true) String requestId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tenantId", required = false) String tenantId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "health", required = false) io.ololabs.toolgate.contracts.ClientHealth health,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "challenge", required = false) io.ololabs.toolgate.contracts.EndpointEnrollmentPrompt challenge,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "error", required = false) io.ololabs.toolgate.contracts.ErrorCode error
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param requestId canonical requestId value
     * @param tenantId canonical tenantId value
     * @param health canonical health value
     * @param challenge canonical challenge value
     * @param error canonical error value
     */
    public ConsoleClientResponse {
        java.util.Objects.requireNonNull(requestId, "requestId");
    }
}
