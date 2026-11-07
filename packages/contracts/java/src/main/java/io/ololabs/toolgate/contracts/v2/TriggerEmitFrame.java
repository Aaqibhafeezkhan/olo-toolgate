// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** One event; the supervisor wraps it in the CloudEvents envelope.
 *
 * @param kind canonical kind value
 * @param emitSeq canonical emitSeq value
 * @param id canonical id value
 * @param subject canonical subject value
 * @param time canonical time value
 * @param data canonical data value
 * @param position canonical position value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerEmitFrame(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "kind", required = true) String kind,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "emitSeq", required = true) Long emitSeq,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "id", required = false) String id,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "subject", required = false) String subject,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "time", required = false) String time,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "data", required = true) com.fasterxml.jackson.databind.JsonNode data,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "position", required = false) String position
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param kind canonical kind value
     * @param emitSeq canonical emitSeq value
     * @param id canonical id value
     * @param subject canonical subject value
     * @param time canonical time value
     * @param data canonical data value
     * @param position canonical position value
     */
    public TriggerEmitFrame {
        java.util.Objects.requireNonNull(kind, "kind");
        if (kind != null && !java.util.Objects.equals(kind, "EMIT")) throw new IllegalArgumentException("kind must be EMIT");
        java.util.Objects.requireNonNull(emitSeq, "emitSeq");
        java.util.Objects.requireNonNull(data, "data");
    }
}
