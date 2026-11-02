// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Authorized operation snapshot and bounded generation history; a changed source revision invalidates the cursor rather than silently mixing snapshots.
 *
 * @param operation canonical operation value
 * @param attempts canonical attempts value
 * @param effects canonical effects value
 * @param next canonical next value
 * @param truncated canonical truncated value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record EnterpriseOperationHistoryPage(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "operation", required = true) EnterpriseOperationOutcome operation,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "attempts", required = true) java.util.List<EnterpriseAttemptHistory> attempts,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "effects", required = true) java.util.List<EnterpriseEffectHistory> effects,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "next", required = false) String next,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "truncated", required = true) Boolean truncated
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param operation canonical operation value
     * @param attempts canonical attempts value
     * @param effects canonical effects value
     * @param next canonical next value
     * @param truncated canonical truncated value
     */
    public EnterpriseOperationHistoryPage {
        java.util.Objects.requireNonNull(operation, "operation");
        java.util.Objects.requireNonNull(attempts, "attempts");
        attempts = attempts == null ? null : java.util.List.copyOf(attempts);
        java.util.Objects.requireNonNull(effects, "effects");
        effects = effects == null ? null : java.util.List.copyOf(effects);
        java.util.Objects.requireNonNull(truncated, "truncated");
    }
}
