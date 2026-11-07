// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Explicit checkpoint; subject to the same barrier as position.
 *
 * @param kind canonical kind value
 * @param checkpointId canonical checkpointId value
 * @param afterEmitSeq canonical afterEmitSeq value
 * @param state canonical state value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerCheckpointFrame(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "kind", required = true) String kind,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "checkpointId", required = true) Long checkpointId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "afterEmitSeq", required = true) Long afterEmitSeq,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "state", required = true) String state
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param kind canonical kind value
     * @param checkpointId canonical checkpointId value
     * @param afterEmitSeq canonical afterEmitSeq value
     * @param state canonical state value
     */
    public TriggerCheckpointFrame {
        java.util.Objects.requireNonNull(kind, "kind");
        if (kind != null && !java.util.Objects.equals(kind, "CHECKPOINT")) throw new IllegalArgumentException("kind must be CHECKPOINT");
        java.util.Objects.requireNonNull(checkpointId, "checkpointId");
        java.util.Objects.requireNonNull(afterEmitSeq, "afterEmitSeq");
        java.util.Objects.requireNonNull(state, "state");
    }
}
