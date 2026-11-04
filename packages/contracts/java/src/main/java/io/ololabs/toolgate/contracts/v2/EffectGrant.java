// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Issued authority and conservative monotonic send budget, measured from before the issuance request.
 *
 * @param grant canonical grant value
 * @param validForMs canonical validForMs value
 * @param downstreamKey canonical downstreamKey value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record EffectGrant(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "grant", required = true) SignedEffectGrant grant,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "validForMs", required = true) Long validForMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "downstreamKey", required = false) DownstreamEffectKey downstreamKey
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param grant canonical grant value
     * @param validForMs canonical validForMs value
     * @param downstreamKey canonical downstreamKey value
     */
    public EffectGrant {
        java.util.Objects.requireNonNull(grant, "grant");
        java.util.Objects.requireNonNull(validForMs, "validForMs");
    }
}
