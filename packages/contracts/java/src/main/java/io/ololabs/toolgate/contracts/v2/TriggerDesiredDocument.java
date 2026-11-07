// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Signed desired trigger state for one executor; the payload of a compact JWS by the organization deployment key.
 *
 * @param formatVersion canonical formatVersion value
 * @param tenantId canonical tenantId value
 * @param executorId canonical executorId value
 * @param executor canonical executor value
 * @param generation canonical generation value
 * @param issuedAtUnixMs canonical issuedAtUnixMs value
 * @param expiresAtUnixMs canonical expiresAtUnixMs value
 * @param instances canonical instances value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerDesiredDocument(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "formatVersion", required = true) Long formatVersion,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tenantId", required = true) String tenantId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "executorId", required = true) String executorId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "executor", required = true) TriggerExecutor executor,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "generation", required = true) Long generation,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "issuedAtUnixMs", required = true) Long issuedAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "expiresAtUnixMs", required = true) Long expiresAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "instances", required = true) java.util.List<TriggerDesiredInstance> instances
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param formatVersion canonical formatVersion value
     * @param tenantId canonical tenantId value
     * @param executorId canonical executorId value
     * @param executor canonical executor value
     * @param generation canonical generation value
     * @param issuedAtUnixMs canonical issuedAtUnixMs value
     * @param expiresAtUnixMs canonical expiresAtUnixMs value
     * @param instances canonical instances value
     */
    public TriggerDesiredDocument {
        java.util.Objects.requireNonNull(formatVersion, "formatVersion");
        if (formatVersion != null && !java.util.Objects.equals(formatVersion, 1L)) throw new IllegalArgumentException("formatVersion must be 1");
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        java.util.Objects.requireNonNull(executorId, "executorId");
        java.util.Objects.requireNonNull(executor, "executor");
        java.util.Objects.requireNonNull(generation, "generation");
        java.util.Objects.requireNonNull(issuedAtUnixMs, "issuedAtUnixMs");
        java.util.Objects.requireNonNull(expiresAtUnixMs, "expiresAtUnixMs");
        java.util.Objects.requireNonNull(instances, "instances");
        instances = instances == null ? null : java.util.List.copyOf(instances);
    }
}
