// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Effect settings with defaults resolved.
 *
 * @param safety canonical safety value
 * @param destructive canonical destructive value
 * @param openWorld canonical openWorld value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ToolEffect(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "safety", required = true) EffectSafety safety,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "destructive", required = true) Boolean destructive,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "openWorld", required = true) Boolean openWorld
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param safety canonical safety value
     * @param destructive canonical destructive value
     * @param openWorld canonical openWorld value
     */
    public ToolEffect {
        java.util.Objects.requireNonNull(safety, "safety");
        java.util.Objects.requireNonNull(destructive, "destructive");
        java.util.Objects.requireNonNull(openWorld, "openWorld");
    }
}
