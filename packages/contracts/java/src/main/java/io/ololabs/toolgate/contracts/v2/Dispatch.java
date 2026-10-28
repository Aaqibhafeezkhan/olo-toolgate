// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Gateway to Tool Host.
 *
 * @param permit canonical permit value
 * @param arguments canonical arguments value
 * @param traceparent canonical traceparent value
 * @param executionContext canonical executionContext value
 * @param collectSpans canonical collectSpans value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record Dispatch(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "permit", required = true) SignedInvocationPermit permit,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "arguments", required = true) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> arguments,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "traceparent", required = false) String traceparent,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "executionContext", required = false) ExecutionContext executionContext,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "collectSpans", required = false) Boolean collectSpans
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param permit canonical permit value
     * @param arguments canonical arguments value
     * @param traceparent canonical traceparent value
     * @param executionContext canonical executionContext value
     * @param collectSpans canonical collectSpans value
     */
    public Dispatch {
        java.util.Objects.requireNonNull(permit, "permit");
        java.util.Objects.requireNonNull(arguments, "arguments");
        arguments = arguments == null ? null : java.util.Map.copyOf(arguments);
    }
}
