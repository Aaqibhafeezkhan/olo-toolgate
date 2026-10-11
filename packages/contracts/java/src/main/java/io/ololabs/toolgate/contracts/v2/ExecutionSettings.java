// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Execution settings with defaults resolved.
 *
 * @param async canonical async value
 * @param maxDurationMs canonical maxDurationMs value
 * @param stateless canonical stateless value
 * @param maxConcurrency canonical maxConcurrency value
 * @param cancelIfAbandoned canonical cancelIfAbandoned value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ExecutionSettings(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "async", required = true) Boolean async,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "maxDurationMs", required = true) Long maxDurationMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "stateless", required = true) Boolean stateless,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "maxConcurrency", required = false) Long maxConcurrency,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "cancelIfAbandoned", required = true) Boolean cancelIfAbandoned
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param async canonical async value
     * @param maxDurationMs canonical maxDurationMs value
     * @param stateless canonical stateless value
     * @param maxConcurrency canonical maxConcurrency value
     * @param cancelIfAbandoned canonical cancelIfAbandoned value
     */
    public ExecutionSettings {
        java.util.Objects.requireNonNull(async, "async");
        java.util.Objects.requireNonNull(maxDurationMs, "maxDurationMs");
        java.util.Objects.requireNonNull(stateless, "stateless");
        java.util.Objects.requireNonNull(cancelIfAbandoned, "cancelIfAbandoned");
    }
}
