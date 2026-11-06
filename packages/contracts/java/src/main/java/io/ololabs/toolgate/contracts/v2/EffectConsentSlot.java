// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Explicit bounded route allowance. Each distinct effect identity reserves one use atomically with its authorization; another generation of the same identity reuses that use.
 *
 * @param slotIndex canonical slotIndex value
 * @param request canonical request value
 * @param permittedCount canonical permittedCount value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record EffectConsentSlot(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "slotIndex", required = true) Long slotIndex,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "request", required = true) EffectConsentMappedRequest request,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "permittedCount", required = true) Long permittedCount
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param slotIndex canonical slotIndex value
     * @param request canonical request value
     * @param permittedCount canonical permittedCount value
     */
    public EffectConsentSlot {
        java.util.Objects.requireNonNull(slotIndex, "slotIndex");
        java.util.Objects.requireNonNull(request, "request");
        java.util.Objects.requireNonNull(permittedCount, "permittedCount");
    }
}
