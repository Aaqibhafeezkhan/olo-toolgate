// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** OS-authenticated native request scoped to a console origin and optional expected UUID. The service validates protected local tenant/origin binding before reading health or starting enrollment.
 *
 * @param kind canonical kind value
 * @param requestId canonical requestId value
 * @param operation canonical operation value
 * @param consoleUrl canonical consoleUrl value
 * @param expectedTenantId canonical expectedTenantId value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ConsoleClientRequest(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "kind", required = true) String kind,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "requestId", required = true) String requestId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "operation", required = true) ConsoleClientOperation operation,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "consoleUrl", required = true) String consoleUrl,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "expectedTenantId", required = false) String expectedTenantId
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param kind canonical kind value
     * @param requestId canonical requestId value
     * @param operation canonical operation value
     * @param consoleUrl canonical consoleUrl value
     * @param expectedTenantId canonical expectedTenantId value
     */
    public ConsoleClientRequest {
        java.util.Objects.requireNonNull(kind, "kind");
        if (kind != null && !java.util.Objects.equals(kind, "console-tenant-state")) throw new IllegalArgumentException("kind must be console-tenant-state");
        java.util.Objects.requireNonNull(requestId, "requestId");
        java.util.Objects.requireNonNull(operation, "operation");
        java.util.Objects.requireNonNull(consoleUrl, "consoleUrl");
    }
}
