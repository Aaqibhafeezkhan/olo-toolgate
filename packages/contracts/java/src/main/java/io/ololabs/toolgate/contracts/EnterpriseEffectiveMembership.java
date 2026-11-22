// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Current group membership provenance with intact persisted grants and Control-derived super-admin- entitlements for live tenant Super Admins. Derived entitlements are not stored directory grants. Candidate grants still require the complete request evaluation.
 *
 * @param groupType canonical groupType value
 * @param groupId canonical groupId value
 * @param enabled canonical enabled value
 * @param roleIds canonical roleIds value
 * @param grants canonical grants value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record EnterpriseEffectiveMembership(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "groupType", required = true) EnterpriseGroupType groupType,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "groupId", required = true) String groupId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "enabled", required = true) Boolean enabled,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "roleIds", required = true) java.util.List<String> roleIds,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "grants", required = true) java.util.List<ControlAccessGrant> grants
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param groupType canonical groupType value
     * @param groupId canonical groupId value
     * @param enabled canonical enabled value
     * @param roleIds canonical roleIds value
     * @param grants canonical grants value
     */
    public EnterpriseEffectiveMembership {
        java.util.Objects.requireNonNull(groupType, "groupType");
        java.util.Objects.requireNonNull(groupId, "groupId");
        java.util.Objects.requireNonNull(enabled, "enabled");
        java.util.Objects.requireNonNull(roleIds, "roleIds");
        roleIds = roleIds == null ? null : java.util.List.copyOf(roleIds);
        java.util.Objects.requireNonNull(grants, "grants");
        grants = grants == null ? null : java.util.List.copyOf(grants);
    }
}
