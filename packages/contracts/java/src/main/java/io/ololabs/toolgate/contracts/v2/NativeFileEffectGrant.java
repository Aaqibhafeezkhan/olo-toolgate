// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** ADR 075 NativeFileEffectGrant; only protected qualified filesystem broker evidence.
 *
 * @param effectId canonical effectId value
 * @param intentDigest canonical intentDigest value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record NativeFileEffectGrant(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "effectId", required = true) String effectId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "intentDigest", required = true) String intentDigest
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param effectId canonical effectId value
     * @param intentDigest canonical intentDigest value
     */
    public NativeFileEffectGrant {
        java.util.Objects.requireNonNull(effectId, "effectId");
        java.util.Objects.requireNonNull(intentDigest, "intentDigest");
    }
}
