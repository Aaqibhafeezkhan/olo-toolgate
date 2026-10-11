// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Supported tool protocol range, inclusive.
 *
 * @param min canonical min value
 * @param max canonical max value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ProtocolRange(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "min", required = true) Long min,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "max", required = true) Long max
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param min canonical min value
     * @param max canonical max value
     */
    public ProtocolRange {
        java.util.Objects.requireNonNull(min, "min");
        java.util.Objects.requireNonNull(max, "max");
    }
}
