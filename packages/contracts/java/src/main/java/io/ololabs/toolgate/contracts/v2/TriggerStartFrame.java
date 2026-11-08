// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** First stdin line, exactly once.
 *
 * @param kind canonical kind value
 * @param triggerId canonical triggerId value
 * @param instanceId canonical instanceId value
 * @param configRevision canonical configRevision value
 * @param leaseEpoch canonical leaseEpoch value
 * @param config canonical config value
 * @param globals canonical globals value
 * @param secretHandles canonical secretHandles value
 * @param checkpoint canonical checkpoint value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerStartFrame(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "kind", required = true) String kind,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "triggerId", required = true) String triggerId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "instanceId", required = true) String instanceId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "configRevision", required = true) Long configRevision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "leaseEpoch", required = true) Long leaseEpoch,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "config", required = true) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> config,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "globals", required = false) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> globals,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "secretHandles", required = false) java.util.List<String> secretHandles,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "checkpoint", required = false) String checkpoint
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param kind canonical kind value
     * @param triggerId canonical triggerId value
     * @param instanceId canonical instanceId value
     * @param configRevision canonical configRevision value
     * @param leaseEpoch canonical leaseEpoch value
     * @param config canonical config value
     * @param globals canonical globals value
     * @param secretHandles canonical secretHandles value
     * @param checkpoint canonical checkpoint value
     */
    public TriggerStartFrame {
        java.util.Objects.requireNonNull(kind, "kind");
        if (kind != null && !java.util.Objects.equals(kind, "TRIGGER_START")) throw new IllegalArgumentException("kind must be TRIGGER_START");
        java.util.Objects.requireNonNull(triggerId, "triggerId");
        java.util.Objects.requireNonNull(instanceId, "instanceId");
        java.util.Objects.requireNonNull(configRevision, "configRevision");
        java.util.Objects.requireNonNull(leaseEpoch, "leaseEpoch");
        java.util.Objects.requireNonNull(config, "config");
        config = config == null ? null : java.util.Map.copyOf(config);
        globals = globals == null ? null : java.util.Map.copyOf(globals);
        secretHandles = secretHandles == null ? null : java.util.List.copyOf(secretHandles);
    }
}
