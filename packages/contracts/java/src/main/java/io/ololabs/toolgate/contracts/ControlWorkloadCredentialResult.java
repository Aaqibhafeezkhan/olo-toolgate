// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Updated binding and the Gateway request context to install with the token; bindingId is the execution binding the agent's grants select, when exactly one does.
 *
 * @param binding canonical binding value
 * @param context canonical context value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ControlWorkloadCredentialResult(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "binding", required = true) ControlWorkloadBinding binding,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "context", required = true) io.ololabs.toolgate.contracts.v2.RequestContext context
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param binding canonical binding value
     * @param context canonical context value
     */
    public ControlWorkloadCredentialResult {
        java.util.Objects.requireNonNull(binding, "binding");
        java.util.Objects.requireNonNull(context, "context");
    }
}
