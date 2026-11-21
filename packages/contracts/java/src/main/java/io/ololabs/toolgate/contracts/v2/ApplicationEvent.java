// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Durable structural notification. RESULT announces an available result; read the separately authorized result endpoint for private output.
 *
 * @param eventId canonical eventId value
 * @param tenantId canonical tenantId value
 * @param sourceType canonical sourceType value
 * @param sourceId canonical sourceId value
 * @param sequence canonical sequence value
 * @param kind canonical kind value
 * @param createdAtUnixMs canonical createdAtUnixMs value
 * @param data canonical data value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ApplicationEvent(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "eventId", required = true) String eventId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tenantId", required = true) String tenantId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "sourceType", required = true) ApplicationEventSource sourceType,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "sourceId", required = true) String sourceId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "sequence", required = true) Long sequence,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "kind", required = true) ApplicationEventKind kind,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "createdAtUnixMs", required = true) Long createdAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "data", required = true) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> data
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param eventId canonical eventId value
     * @param tenantId canonical tenantId value
     * @param sourceType canonical sourceType value
     * @param sourceId canonical sourceId value
     * @param sequence canonical sequence value
     * @param kind canonical kind value
     * @param createdAtUnixMs canonical createdAtUnixMs value
     * @param data canonical data value
     */
    public ApplicationEvent {
        java.util.Objects.requireNonNull(eventId, "eventId");
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        java.util.Objects.requireNonNull(sourceType, "sourceType");
        java.util.Objects.requireNonNull(sourceId, "sourceId");
        java.util.Objects.requireNonNull(sequence, "sequence");
        java.util.Objects.requireNonNull(kind, "kind");
        java.util.Objects.requireNonNull(createdAtUnixMs, "createdAtUnixMs");
        java.util.Objects.requireNonNull(data, "data");
        data = data == null ? null : java.util.Map.copyOf(data);
    }
}
