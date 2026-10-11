// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Deployment binding.
 *
 * @param bindingFormatVersion canonical bindingFormatVersion value
 * @param bindingId canonical bindingId value
 * @param revision canonical revision value
 * @param tenantId canonical tenantId value
 * @param packageId canonical packageId value
 * @param packageDigest canonical packageDigest value
 * @param configRevision canonical configRevision value
 * @param serviceProfileDigest canonical serviceProfileDigest value
 * @param serviceProfileRevision canonical serviceProfileRevision value
 * @param target canonical target value
 * @param grants canonical grants value
 * @param limitOverrides canonical limitOverrides value
 * @param rolloutPlan canonical rolloutPlan value
 * @param effects canonical effects value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record DeploymentBinding(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "bindingFormatVersion", required = true) Long bindingFormatVersion,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "bindingId", required = true) String bindingId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "revision", required = true) Long revision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tenantId", required = true) String tenantId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageId", required = true) String packageId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageDigest", required = true) String packageDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "configRevision", required = true) Long configRevision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "serviceProfileDigest", required = true) String serviceProfileDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "serviceProfileRevision", required = true) Long serviceProfileRevision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "target", required = true) DeploymentTarget target,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "grants", required = true) java.util.List<DeploymentGrant> grants,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "limitOverrides", required = true) java.util.List<LimitOverride> limitOverrides,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "rolloutPlan", required = true) RolloutPlan rolloutPlan,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "effects", required = true) java.util.List<EffectApproval> effects
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param bindingFormatVersion canonical bindingFormatVersion value
     * @param bindingId canonical bindingId value
     * @param revision canonical revision value
     * @param tenantId canonical tenantId value
     * @param packageId canonical packageId value
     * @param packageDigest canonical packageDigest value
     * @param configRevision canonical configRevision value
     * @param serviceProfileDigest canonical serviceProfileDigest value
     * @param serviceProfileRevision canonical serviceProfileRevision value
     * @param target canonical target value
     * @param grants canonical grants value
     * @param limitOverrides canonical limitOverrides value
     * @param rolloutPlan canonical rolloutPlan value
     * @param effects canonical effects value
     */
    public DeploymentBinding {
        java.util.Objects.requireNonNull(bindingFormatVersion, "bindingFormatVersion");
        if (bindingFormatVersion != null && !java.util.Objects.equals(bindingFormatVersion, 1L)) throw new IllegalArgumentException("bindingFormatVersion must be 1");
        java.util.Objects.requireNonNull(bindingId, "bindingId");
        java.util.Objects.requireNonNull(revision, "revision");
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        java.util.Objects.requireNonNull(packageId, "packageId");
        java.util.Objects.requireNonNull(packageDigest, "packageDigest");
        java.util.Objects.requireNonNull(configRevision, "configRevision");
        java.util.Objects.requireNonNull(serviceProfileDigest, "serviceProfileDigest");
        java.util.Objects.requireNonNull(serviceProfileRevision, "serviceProfileRevision");
        java.util.Objects.requireNonNull(target, "target");
        java.util.Objects.requireNonNull(grants, "grants");
        grants = grants == null ? null : java.util.List.copyOf(grants);
        java.util.Objects.requireNonNull(limitOverrides, "limitOverrides");
        limitOverrides = limitOverrides == null ? null : java.util.List.copyOf(limitOverrides);
        java.util.Objects.requireNonNull(rolloutPlan, "rolloutPlan");
        java.util.Objects.requireNonNull(effects, "effects");
        effects = effects == null ? null : java.util.List.copyOf(effects);
    }
}
