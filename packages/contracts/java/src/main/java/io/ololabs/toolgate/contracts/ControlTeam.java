// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Tenant-scoped teams configuration record. Not a runtime credential or policy grant.
 *
 * @param id canonical id value
 * @param name canonical name value
 * @param enabled canonical enabled value
 * @param revision canonical revision value
 * @param userIds canonical userIds value
 * @param roleIds canonical roleIds value
 * @param dataClearance canonical dataClearance value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ControlTeam(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "id", required = true) String id,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "name", required = true) String name,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "enabled", required = true) Boolean enabled,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "revision", required = true) Long revision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "userIds", required = true) java.util.List<String> userIds,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "roleIds", required = true) java.util.List<String> roleIds,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "dataClearance", required = false) ControlDataClearance dataClearance
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param id canonical id value
     * @param name canonical name value
     * @param enabled canonical enabled value
     * @param revision canonical revision value
     * @param userIds canonical userIds value
     * @param roleIds canonical roleIds value
     * @param dataClearance canonical dataClearance value
     */
    public ControlTeam {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(name, "name");
        java.util.Objects.requireNonNull(enabled, "enabled");
        java.util.Objects.requireNonNull(revision, "revision");
        java.util.Objects.requireNonNull(userIds, "userIds");
        userIds = userIds == null ? null : java.util.List.copyOf(userIds);
        java.util.Objects.requireNonNull(roleIds, "roleIds");
        roleIds = roleIds == null ? null : java.util.List.copyOf(roleIds);
    }
}
