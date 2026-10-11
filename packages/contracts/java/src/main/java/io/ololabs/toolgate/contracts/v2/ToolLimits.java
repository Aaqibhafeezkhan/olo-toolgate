// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Tool resource requests; each at most the platform ceiling (plan.md §13.5).
 *
 * @param memoryBytes canonical memoryBytes value
 * @param maxInputBytes canonical maxInputBytes value
 * @param maxOutputBytes canonical maxOutputBytes value
 * @param maxArgumentProperties canonical maxArgumentProperties value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ToolLimits(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "memoryBytes", required = false) Long memoryBytes,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "maxInputBytes", required = false) Long maxInputBytes,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "maxOutputBytes", required = false) Long maxOutputBytes,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "maxArgumentProperties", required = false) Long maxArgumentProperties
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param memoryBytes canonical memoryBytes value
     * @param maxInputBytes canonical maxInputBytes value
     * @param maxOutputBytes canonical maxOutputBytes value
     * @param maxArgumentProperties canonical maxArgumentProperties value
     */
    public ToolLimits {
    }
}
