// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** How one required identity is resolved on import. MAP needs targetUserId; teamIds are added, never replaced.
 *
 * @param action canonical action value
 * @param targetUserId canonical targetUserId value
 * @param teamIds canonical teamIds value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ControlIdentityResolution(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "action", required = true) ControlIdentityResolutionAction action,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "targetUserId", required = false) String targetUserId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "teamIds", required = false) java.util.List<String> teamIds
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param action canonical action value
     * @param targetUserId canonical targetUserId value
     * @param teamIds canonical teamIds value
     */
    public ControlIdentityResolution {
        java.util.Objects.requireNonNull(action, "action");
        teamIds = teamIds == null ? null : java.util.List.copyOf(teamIds);
    }
}
