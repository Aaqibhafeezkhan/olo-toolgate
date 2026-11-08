// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Server-assigned assignment metadata.
 *
 * @param name canonical name value
 * @param uid canonical uid value
 * @param generation canonical generation value
 * @param configRevision canonical configRevision value
 * @param resourceVersion canonical resourceVersion value
 * @param labels canonical labels value
 * @param createdAtUnixMs canonical createdAtUnixMs value
 * @param updatedAtUnixMs canonical updatedAtUnixMs value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerAssignmentMetadata(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "name", required = true) String name,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "uid", required = true) String uid,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "generation", required = true) Long generation,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "configRevision", required = true) Long configRevision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "resourceVersion", required = true) String resourceVersion,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "labels", required = true) java.util.List<TriggerLabel> labels,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "createdAtUnixMs", required = true) Long createdAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "updatedAtUnixMs", required = true) Long updatedAtUnixMs
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param name canonical name value
     * @param uid canonical uid value
     * @param generation canonical generation value
     * @param configRevision canonical configRevision value
     * @param resourceVersion canonical resourceVersion value
     * @param labels canonical labels value
     * @param createdAtUnixMs canonical createdAtUnixMs value
     * @param updatedAtUnixMs canonical updatedAtUnixMs value
     */
    public TriggerAssignmentMetadata {
        java.util.Objects.requireNonNull(name, "name");
        java.util.Objects.requireNonNull(uid, "uid");
        java.util.Objects.requireNonNull(generation, "generation");
        java.util.Objects.requireNonNull(configRevision, "configRevision");
        java.util.Objects.requireNonNull(resourceVersion, "resourceVersion");
        java.util.Objects.requireNonNull(labels, "labels");
        labels = labels == null ? null : java.util.List.copyOf(labels);
        java.util.Objects.requireNonNull(createdAtUnixMs, "createdAtUnixMs");
        java.util.Objects.requireNonNull(updatedAtUnixMs, "updatedAtUnixMs");
    }
}
