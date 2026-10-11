// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Reviewed effect class per tool.
 *
 * @param toolId canonical toolId value
 * @param effectSafety canonical effectSafety value
 * @param businessKeyNamespace canonical businessKeyNamespace value
 * @param missingKeyPolicy canonical missingKeyPolicy value
 * @param approvalId canonical approvalId value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record EffectApproval(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toolId", required = true) String toolId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "effectSafety", required = true) EffectSafety effectSafety,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "businessKeyNamespace", required = true) KeyNamespace businessKeyNamespace,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "missingKeyPolicy", required = true) MissingKeyPolicy missingKeyPolicy,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "approvalId", required = true) String approvalId
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param toolId canonical toolId value
     * @param effectSafety canonical effectSafety value
     * @param businessKeyNamespace canonical businessKeyNamespace value
     * @param missingKeyPolicy canonical missingKeyPolicy value
     * @param approvalId canonical approvalId value
     */
    public EffectApproval {
        java.util.Objects.requireNonNull(toolId, "toolId");
        java.util.Objects.requireNonNull(effectSafety, "effectSafety");
        java.util.Objects.requireNonNull(businessKeyNamespace, "businessKeyNamespace");
        java.util.Objects.requireNonNull(missingKeyPolicy, "missingKeyPolicy");
        java.util.Objects.requireNonNull(approvalId, "approvalId");
    }
}
