// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Signed semantic descriptor of one trigger export (plan 5.9).
 *
 * @param kind canonical kind value
 * @param triggerId canonical triggerId value
 * @param mode canonical mode value
 * @param executors canonical executors value
 * @param eventType canonical eventType value
 * @param lossTolerant canonical lossTolerant value
 * @param configSchema canonical configSchema value
 * @param eventSchema canonical eventSchema value
 * @param checkpointSchemaVersion canonical checkpointSchemaVersion value
 * @param sourceReplay canonical sourceReplay value
 * @param schedule canonical schedule value
 * @param limits canonical limits value
 * @param overflow canonical overflow value
 * @param onTerminalRejection canonical onTerminalRejection value
 * @param snapshot canonical snapshot value
 * @param routes canonical routes value
 * @param tcpDestinations canonical tcpDestinations value
 * @param secrets canonical secrets value
 * @param permissions canonical permissions value
 * @param tiers canonical tiers value
 * @param selfTests canonical selfTests value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerDescriptor(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "kind", required = true) TriggerKind kind,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "triggerId", required = true) String triggerId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "mode", required = true) TriggerMode mode,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "executors", required = true) java.util.List<TriggerExecutor> executors,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "eventType", required = true) String eventType,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "lossTolerant", required = false) Boolean lossTolerant,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "configSchema", required = true) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> configSchema,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "eventSchema", required = true) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> eventSchema,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "checkpointSchemaVersion", required = true) Long checkpointSchemaVersion,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "sourceReplay", required = true) TriggerSourceReplay sourceReplay,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "schedule", required = true) TriggerSchedule schedule,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "limits", required = true) TriggerLimits limits,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "overflow", required = true) TriggerOverflow overflow,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "onTerminalRejection", required = true) TriggerTerminalRejectionPolicy onTerminalRejection,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "snapshot", required = false) TriggerSnapshotCapacity snapshot,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "routes", required = false) java.util.List<String> routes,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tcpDestinations", required = false) java.util.List<String> tcpDestinations,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "secrets", required = false) java.util.List<String> secrets,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "permissions", required = false) java.util.List<String> permissions,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tiers", required = false) java.util.List<String> tiers,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "selfTests", required = false) java.util.List<String> selfTests
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param kind canonical kind value
     * @param triggerId canonical triggerId value
     * @param mode canonical mode value
     * @param executors canonical executors value
     * @param eventType canonical eventType value
     * @param lossTolerant canonical lossTolerant value
     * @param configSchema canonical configSchema value
     * @param eventSchema canonical eventSchema value
     * @param checkpointSchemaVersion canonical checkpointSchemaVersion value
     * @param sourceReplay canonical sourceReplay value
     * @param schedule canonical schedule value
     * @param limits canonical limits value
     * @param overflow canonical overflow value
     * @param onTerminalRejection canonical onTerminalRejection value
     * @param snapshot canonical snapshot value
     * @param routes canonical routes value
     * @param tcpDestinations canonical tcpDestinations value
     * @param secrets canonical secrets value
     * @param permissions canonical permissions value
     * @param tiers canonical tiers value
     * @param selfTests canonical selfTests value
     */
    public TriggerDescriptor {
        java.util.Objects.requireNonNull(kind, "kind");
        java.util.Objects.requireNonNull(triggerId, "triggerId");
        java.util.Objects.requireNonNull(mode, "mode");
        java.util.Objects.requireNonNull(executors, "executors");
        executors = executors == null ? null : java.util.List.copyOf(executors);
        java.util.Objects.requireNonNull(eventType, "eventType");
        java.util.Objects.requireNonNull(configSchema, "configSchema");
        configSchema = configSchema == null ? null : java.util.Map.copyOf(configSchema);
        java.util.Objects.requireNonNull(eventSchema, "eventSchema");
        eventSchema = eventSchema == null ? null : java.util.Map.copyOf(eventSchema);
        java.util.Objects.requireNonNull(checkpointSchemaVersion, "checkpointSchemaVersion");
        java.util.Objects.requireNonNull(sourceReplay, "sourceReplay");
        java.util.Objects.requireNonNull(schedule, "schedule");
        java.util.Objects.requireNonNull(limits, "limits");
        java.util.Objects.requireNonNull(overflow, "overflow");
        java.util.Objects.requireNonNull(onTerminalRejection, "onTerminalRejection");
        routes = routes == null ? null : java.util.List.copyOf(routes);
        tcpDestinations = tcpDestinations == null ? null : java.util.List.copyOf(tcpDestinations);
        secrets = secrets == null ? null : java.util.List.copyOf(secrets);
        permissions = permissions == null ? null : java.util.List.copyOf(permissions);
        tiers = tiers == null ? null : java.util.List.copyOf(tiers);
        selfTests = selfTests == null ? null : java.util.List.copyOf(selfTests);
    }
}
