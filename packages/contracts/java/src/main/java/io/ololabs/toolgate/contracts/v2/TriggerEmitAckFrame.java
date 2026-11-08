// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Completes or fails one emit() future.
 *
 * @param kind canonical kind value
 * @param emitSeq canonical emitSeq value
 * @param status canonical status value
 * @param reason canonical reason value
 * @param outboxSeq canonical outboxSeq value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerEmitAckFrame(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "kind", required = true) String kind,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "emitSeq", required = true) Long emitSeq,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "status", required = true) TriggerEmitStatus status,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "reason", required = false) String reason,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "outboxSeq", required = false) Long outboxSeq
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param kind canonical kind value
     * @param emitSeq canonical emitSeq value
     * @param status canonical status value
     * @param reason canonical reason value
     * @param outboxSeq canonical outboxSeq value
     */
    public TriggerEmitAckFrame {
        java.util.Objects.requireNonNull(kind, "kind");
        if (kind != null && !java.util.Objects.equals(kind, "EMIT_ACK")) throw new IllegalArgumentException("kind must be EMIT_ACK");
        java.util.Objects.requireNonNull(emitSeq, "emitSeq");
        java.util.Objects.requireNonNull(status, "status");
    }
}
