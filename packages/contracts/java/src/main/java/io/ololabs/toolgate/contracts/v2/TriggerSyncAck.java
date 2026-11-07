// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Trigger block of the check-in ack (plan 8.3, 8.5).
 *
 * @param generation canonical generation value
 * @param maxCheckInBytes canonical maxCheckInBytes value
 * @param checkInNow canonical checkInNow value
 * @param acked canonical acked value
 * @param dispositions canonical dispositions value
 * @param retryLater canonical retryLater value
 * @param leases canonical leases value
 * @param revoked canonical revoked value
 * @param rejected canonical rejected value
 * @param snapshotPages canonical snapshotPages value
 * @param snapshotRequested canonical snapshotRequested value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerSyncAck(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "generation", required = true) Long generation,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "maxCheckInBytes", required = true) Long maxCheckInBytes,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "checkInNow", required = false) Boolean checkInNow,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "acked", required = false) java.util.List<TriggerOutboxAck> acked,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "dispositions", required = false) java.util.List<TriggerDisposition> dispositions,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "retryLater", required = false) java.util.List<TriggerRetryLater> retryLater,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "leases", required = false) java.util.List<TriggerLease> leases,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "revoked", required = false) java.util.List<TriggerRevocation> revoked,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "rejected", required = false) java.util.List<TriggerRejection> rejected,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "snapshotPages", required = false) java.util.List<TriggerSnapshotPageAck> snapshotPages,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "snapshotRequested", required = false) Boolean snapshotRequested
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param generation canonical generation value
     * @param maxCheckInBytes canonical maxCheckInBytes value
     * @param checkInNow canonical checkInNow value
     * @param acked canonical acked value
     * @param dispositions canonical dispositions value
     * @param retryLater canonical retryLater value
     * @param leases canonical leases value
     * @param revoked canonical revoked value
     * @param rejected canonical rejected value
     * @param snapshotPages canonical snapshotPages value
     * @param snapshotRequested canonical snapshotRequested value
     */
    public TriggerSyncAck {
        java.util.Objects.requireNonNull(generation, "generation");
        java.util.Objects.requireNonNull(maxCheckInBytes, "maxCheckInBytes");
        acked = acked == null ? null : java.util.List.copyOf(acked);
        dispositions = dispositions == null ? null : java.util.List.copyOf(dispositions);
        retryLater = retryLater == null ? null : java.util.List.copyOf(retryLater);
        leases = leases == null ? null : java.util.List.copyOf(leases);
        revoked = revoked == null ? null : java.util.List.copyOf(revoked);
        rejected = rejected == null ? null : java.util.List.copyOf(rejected);
        snapshotPages = snapshotPages == null ? null : java.util.List.copyOf(snapshotPages);
    }
}
