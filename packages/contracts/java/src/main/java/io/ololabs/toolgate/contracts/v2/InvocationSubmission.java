// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** v2 body of POST /access/invocations (invocation-state-v2.md T1). The Gateway generates invocationId; Control evaluates policy, reserves the business key and decides whether a task exists.
 *
 * @param invocationId canonical invocationId value
 * @param context canonical context value
 * @param catalogScope canonical catalogScope value
 * @param toolId canonical toolId value
 * @param toolDescriptorDigest canonical toolDescriptorDigest value
 * @param packageDigest canonical packageDigest value
 * @param arguments canonical arguments value
 * @param argumentsDigest canonical argumentsDigest value
 * @param businessKey canonical businessKey value
 * @param tasks canonical tasks value
 * @param requestState canonical requestState value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record InvocationSubmission(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "invocationId", required = true) String invocationId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "context", required = true) RequestContext context,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "catalogScope", required = true) CatalogScope catalogScope,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toolId", required = true) String toolId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toolDescriptorDigest", required = true) String toolDescriptorDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageDigest", required = true) String packageDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "arguments", required = true) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> arguments,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "argumentsDigest", required = true) String argumentsDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "businessKey", required = false) BusinessKeyReservationRequest businessKey,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tasks", required = true) TaskCapability tasks,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "requestState", required = false) String requestState
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param invocationId canonical invocationId value
     * @param context canonical context value
     * @param catalogScope canonical catalogScope value
     * @param toolId canonical toolId value
     * @param toolDescriptorDigest canonical toolDescriptorDigest value
     * @param packageDigest canonical packageDigest value
     * @param arguments canonical arguments value
     * @param argumentsDigest canonical argumentsDigest value
     * @param businessKey canonical businessKey value
     * @param tasks canonical tasks value
     * @param requestState canonical requestState value
     */
    public InvocationSubmission {
        java.util.Objects.requireNonNull(invocationId, "invocationId");
        java.util.Objects.requireNonNull(context, "context");
        java.util.Objects.requireNonNull(catalogScope, "catalogScope");
        java.util.Objects.requireNonNull(toolId, "toolId");
        java.util.Objects.requireNonNull(toolDescriptorDigest, "toolDescriptorDigest");
        java.util.Objects.requireNonNull(packageDigest, "packageDigest");
        java.util.Objects.requireNonNull(arguments, "arguments");
        arguments = arguments == null ? null : java.util.Map.copyOf(arguments);
        java.util.Objects.requireNonNull(argumentsDigest, "argumentsDigest");
        java.util.Objects.requireNonNull(tasks, "tasks");
    }
}
