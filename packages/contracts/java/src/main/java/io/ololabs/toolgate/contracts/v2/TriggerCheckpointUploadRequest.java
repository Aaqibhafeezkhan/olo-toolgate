// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Start a checkpoint upload; idempotent on (instanceId, version).
 *
 * @param version canonical version value
 * @param leaseEpoch canonical leaseEpoch value
 * @param afterOutboxSeq canonical afterOutboxSeq value
 * @param level canonical level value
 * @param sizeBytes canonical sizeBytes value
 * @param digest canonical digest value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerCheckpointUploadRequest(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "version", required = true) Long version,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "leaseEpoch", required = true) Long leaseEpoch,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "afterOutboxSeq", required = true) Long afterOutboxSeq,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "level", required = true) TriggerCheckpointLevel level,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "sizeBytes", required = true) Long sizeBytes,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "digest", required = true) String digest
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param version canonical version value
     * @param leaseEpoch canonical leaseEpoch value
     * @param afterOutboxSeq canonical afterOutboxSeq value
     * @param level canonical level value
     * @param sizeBytes canonical sizeBytes value
     * @param digest canonical digest value
     */
    public TriggerCheckpointUploadRequest {
        java.util.Objects.requireNonNull(version, "version");
        java.util.Objects.requireNonNull(leaseEpoch, "leaseEpoch");
        java.util.Objects.requireNonNull(afterOutboxSeq, "afterOutboxSeq");
        java.util.Objects.requireNonNull(level, "level");
        java.util.Objects.requireNonNull(sizeBytes, "sizeBytes");
        java.util.Objects.requireNonNull(digest, "digest");
    }
}
