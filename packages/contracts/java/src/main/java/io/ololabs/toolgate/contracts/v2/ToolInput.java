// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** First stdin frame.
 *
 * @param protocolVersion canonical protocolVersion value
 * @param invocationId canonical invocationId value
 * @param taskId canonical taskId value
 * @param toolId canonical toolId value
 * @param entryPoint canonical entryPoint value
 * @param arguments canonical arguments value
 * @param globals canonical globals value
 * @param claims canonical claims value
 * @param secretHandles canonical secretHandles value
 * @param deadlineUnixMs canonical deadlineUnixMs value
 * @param idempotencyKey canonical idempotencyKey value
 * @param catalogRevision canonical catalogRevision value
 * @param traceparent canonical traceparent value
 * @param fencingToken canonical fencingToken value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ToolInput(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "protocolVersion", required = true) Long protocolVersion,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "invocationId", required = true) String invocationId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "taskId", required = false) String taskId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toolId", required = true) String toolId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "entryPoint", required = true) String entryPoint,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "arguments", required = true) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> arguments,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "globals", required = true) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> globals,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "claims", required = true) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> claims,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "secretHandles", required = true) java.util.List<SecretHandle> secretHandles,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deadlineUnixMs", required = true) Long deadlineUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "idempotencyKey", required = false) String idempotencyKey,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "catalogRevision", required = true) String catalogRevision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "traceparent", required = false) String traceparent,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "fencingToken", required = false) String fencingToken
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param protocolVersion canonical protocolVersion value
     * @param invocationId canonical invocationId value
     * @param taskId canonical taskId value
     * @param toolId canonical toolId value
     * @param entryPoint canonical entryPoint value
     * @param arguments canonical arguments value
     * @param globals canonical globals value
     * @param claims canonical claims value
     * @param secretHandles canonical secretHandles value
     * @param deadlineUnixMs canonical deadlineUnixMs value
     * @param idempotencyKey canonical idempotencyKey value
     * @param catalogRevision canonical catalogRevision value
     * @param traceparent canonical traceparent value
     * @param fencingToken canonical fencingToken value
     */
    public ToolInput {
        java.util.Objects.requireNonNull(protocolVersion, "protocolVersion");
        if (protocolVersion != null && !java.util.Objects.equals(protocolVersion, 2L)) throw new IllegalArgumentException("protocolVersion must be 2");
        java.util.Objects.requireNonNull(invocationId, "invocationId");
        java.util.Objects.requireNonNull(toolId, "toolId");
        java.util.Objects.requireNonNull(entryPoint, "entryPoint");
        java.util.Objects.requireNonNull(arguments, "arguments");
        arguments = arguments == null ? null : java.util.Map.copyOf(arguments);
        java.util.Objects.requireNonNull(globals, "globals");
        globals = globals == null ? null : java.util.Map.copyOf(globals);
        java.util.Objects.requireNonNull(claims, "claims");
        claims = claims == null ? null : java.util.Map.copyOf(claims);
        java.util.Objects.requireNonNull(secretHandles, "secretHandles");
        secretHandles = secretHandles == null ? null : java.util.List.copyOf(secretHandles);
        java.util.Objects.requireNonNull(deadlineUnixMs, "deadlineUnixMs");
        java.util.Objects.requireNonNull(catalogRevision, "catalogRevision");
    }
}
