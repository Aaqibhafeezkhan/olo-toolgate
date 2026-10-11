// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Latest progress.
 *
 * @param fraction canonical fraction value
 * @param message canonical message value
 * @param atUnixMs canonical atUnixMs value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TaskProgress(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "fraction", required = true) Double fraction,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "message", required = false) String message,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "atUnixMs", required = true) Long atUnixMs
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param fraction canonical fraction value
     * @param message canonical message value
     * @param atUnixMs canonical atUnixMs value
     */
    public TaskProgress {
        java.util.Objects.requireNonNull(fraction, "fraction");
        java.util.Objects.requireNonNull(atUnixMs, "atUnixMs");
    }
}
