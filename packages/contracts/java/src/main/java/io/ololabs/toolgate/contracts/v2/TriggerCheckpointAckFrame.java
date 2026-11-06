// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Barrier advanced to that checkpoint at that level.
 *
 * @param kind canonical kind value
 * @param checkpointId canonical checkpointId value
 * @param level canonical level value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerCheckpointAckFrame(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "kind", required = true) String kind,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "checkpointId", required = true) Long checkpointId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "level", required = true) TriggerCheckpointLevel level
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param kind canonical kind value
     * @param checkpointId canonical checkpointId value
     * @param level canonical level value
     */
    public TriggerCheckpointAckFrame {
        java.util.Objects.requireNonNull(kind, "kind");
        if (kind != null && !java.util.Objects.equals(kind, "CHECKPOINT_ACK")) throw new IllegalArgumentException("kind must be CHECKPOINT_ACK");
        java.util.Objects.requireNonNull(checkpointId, "checkpointId");
        java.util.Objects.requireNonNull(level, "level");
    }
}
