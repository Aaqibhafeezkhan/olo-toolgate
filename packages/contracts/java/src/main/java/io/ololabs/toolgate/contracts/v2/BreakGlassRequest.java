// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Break-glass kill: 2-of-3 operator signatures, adds kills only.
 *
 * @param scope canonical scope value
 * @param mode canonical mode value
 * @param reason canonical reason value
 * @param operatorSignatures canonical operatorSignatures value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record BreakGlassRequest(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "scope", required = true) KillScope scope,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "mode", required = true) KillMode mode,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "reason", required = true) String reason,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "operatorSignatures", required = true) java.util.List<String> operatorSignatures
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param scope canonical scope value
     * @param mode canonical mode value
     * @param reason canonical reason value
     * @param operatorSignatures canonical operatorSignatures value
     */
    public BreakGlassRequest {
        java.util.Objects.requireNonNull(scope, "scope");
        java.util.Objects.requireNonNull(mode, "mode");
        java.util.Objects.requireNonNull(reason, "reason");
        java.util.Objects.requireNonNull(operatorSignatures, "operatorSignatures");
        operatorSignatures = operatorSignatures == null ? null : java.util.List.copyOf(operatorSignatures);
    }
}
