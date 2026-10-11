// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Per-tool limit override; at most the platform ceiling.
 *
 * @param toolId canonical toolId value
 * @param maxDurationMs canonical maxDurationMs value
 * @param maxConcurrency canonical maxConcurrency value
 * @param memoryBytes canonical memoryBytes value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record LimitOverride(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toolId", required = true) String toolId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "maxDurationMs", required = false) Long maxDurationMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "maxConcurrency", required = false) Long maxConcurrency,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "memoryBytes", required = false) Long memoryBytes
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param toolId canonical toolId value
     * @param maxDurationMs canonical maxDurationMs value
     * @param maxConcurrency canonical maxConcurrency value
     * @param memoryBytes canonical memoryBytes value
     */
    public LimitOverride {
        java.util.Objects.requireNonNull(toolId, "toolId");
    }
}
