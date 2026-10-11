// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Effective Phase A check-in values. Env-owned, read-only in the console.
 *
 * @param checkInMs canonical checkInMs value
 * @param jitterPct canonical jitterPct value
 * @param offlineAfterMs canonical offlineAfterMs value
 * @param source canonical source value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ClientCheckIn(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "checkInMs", required = true) Long checkInMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "jitterPct", required = true) Long jitterPct,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "offlineAfterMs", required = true) Long offlineAfterMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "source", required = true) ValueSource source
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param checkInMs canonical checkInMs value
     * @param jitterPct canonical jitterPct value
     * @param offlineAfterMs canonical offlineAfterMs value
     * @param source canonical source value
     */
    public ClientCheckIn {
        java.util.Objects.requireNonNull(checkInMs, "checkInMs");
        java.util.Objects.requireNonNull(jitterPct, "jitterPct");
        java.util.Objects.requireNonNull(offlineAfterMs, "offlineAfterMs");
        java.util.Objects.requireNonNull(source, "source");
    }
}
