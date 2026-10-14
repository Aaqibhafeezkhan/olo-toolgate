// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Management grants belong to group-assigned Management Roles only.
 *
 * @param actions canonical actions value
 * @param groupType canonical groupType value
 * @param groups canonical groups value
 * @param grantableScopes canonical grantableScopes value
 * @param conditions canonical conditions value
 * @param ownDevices canonical ownDevices value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record EnterpriseManagementRule(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "actions", required = true) java.util.List<String> actions,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "groupType", required = true) EnterpriseGroupType groupType,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "groups", required = true) GroupSelection groups,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "grantableScopes", required = true) java.util.List<EnterpriseScope> grantableScopes,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "conditions", required = true) EnterpriseConditions conditions,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "ownDevices", required = false) Boolean ownDevices
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param actions canonical actions value
     * @param groupType canonical groupType value
     * @param groups canonical groups value
     * @param grantableScopes canonical grantableScopes value
     * @param conditions canonical conditions value
     * @param ownDevices canonical ownDevices value
     */
    public EnterpriseManagementRule {
        java.util.Objects.requireNonNull(actions, "actions");
        actions = actions == null ? null : java.util.List.copyOf(actions);
        java.util.Objects.requireNonNull(groupType, "groupType");
        java.util.Objects.requireNonNull(groups, "groups");
        java.util.Objects.requireNonNull(grantableScopes, "grantableScopes");
        grantableScopes = grantableScopes == null ? null : java.util.List.copyOf(grantableScopes);
        java.util.Objects.requireNonNull(conditions, "conditions");
    }
}
