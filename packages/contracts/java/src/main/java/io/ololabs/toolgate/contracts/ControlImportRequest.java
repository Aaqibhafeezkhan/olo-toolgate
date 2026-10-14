// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Validate and diff before applying a bounded configuration transaction.
 *
 * @param snapshot canonical snapshot value
 * @param mode canonical mode value
 * @param dryRun canonical dryRun value
 * @param identityResolution canonical identityResolution value
 * @param removeUnlistedMemberships canonical removeUnlistedMemberships value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ControlImportRequest(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "snapshot", required = true) ControlSnapshot snapshot,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "mode", required = true) ControlImportMode mode,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "dryRun", required = true) Boolean dryRun,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "identityResolution", required = false) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> identityResolution,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "removeUnlistedMemberships", required = false) Boolean removeUnlistedMemberships
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param snapshot canonical snapshot value
     * @param mode canonical mode value
     * @param dryRun canonical dryRun value
     * @param identityResolution canonical identityResolution value
     * @param removeUnlistedMemberships canonical removeUnlistedMemberships value
     */
    public ControlImportRequest {
        java.util.Objects.requireNonNull(snapshot, "snapshot");
        java.util.Objects.requireNonNull(mode, "mode");
        java.util.Objects.requireNonNull(dryRun, "dryRun");
        identityResolution = identityResolution == null ? null : java.util.Map.copyOf(identityResolution);
    }
}
