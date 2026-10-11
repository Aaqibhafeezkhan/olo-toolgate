// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Kept after retention; results purged.
 *
 * @param businessKeyHash canonical businessKeyHash value
 * @param fingerprint canonical fingerprint value
 * @param outcome canonical outcome value
 * @param createdAtUnixMs canonical createdAtUnixMs value
 * @param purgedAtUnixMs canonical purgedAtUnixMs value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record BusinessKeyTombstone(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "businessKeyHash", required = true) String businessKeyHash,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "fingerprint", required = true) String fingerprint,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "outcome", required = true) TaskState outcome,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "createdAtUnixMs", required = true) Long createdAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "purgedAtUnixMs", required = true) Long purgedAtUnixMs
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param businessKeyHash canonical businessKeyHash value
     * @param fingerprint canonical fingerprint value
     * @param outcome canonical outcome value
     * @param createdAtUnixMs canonical createdAtUnixMs value
     * @param purgedAtUnixMs canonical purgedAtUnixMs value
     */
    public BusinessKeyTombstone {
        java.util.Objects.requireNonNull(businessKeyHash, "businessKeyHash");
        java.util.Objects.requireNonNull(fingerprint, "fingerprint");
        java.util.Objects.requireNonNull(outcome, "outcome");
        java.util.Objects.requireNonNull(createdAtUnixMs, "createdAtUnixMs");
        java.util.Objects.requireNonNull(purgedAtUnixMs, "purgedAtUnixMs");
    }
}
