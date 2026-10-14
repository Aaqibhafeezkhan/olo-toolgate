// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** A user ID that exported records reference, with the exported teams it belonged to. IDs only.
 *
 * @param id canonical id value
 * @param teamIds canonical teamIds value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ControlRequiredIdentity(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "id", required = true) String id,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "teamIds", required = true) java.util.List<String> teamIds
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param id canonical id value
     * @param teamIds canonical teamIds value
     */
    public ControlRequiredIdentity {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(teamIds, "teamIds");
        teamIds = teamIds == null ? null : java.util.List.copyOf(teamIds);
    }
}
