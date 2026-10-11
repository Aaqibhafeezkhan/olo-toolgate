// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** GET /api/control/v1/deployments/{bindingId}/effective.
 *
 * @param bindingId canonical bindingId value
 * @param revision canonical revision value
 * @param values canonical values value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record EffectiveDeployment(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "bindingId", required = true) String bindingId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "revision", required = true) Long revision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "values", required = true) java.util.List<EffectiveValue> values
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param bindingId canonical bindingId value
     * @param revision canonical revision value
     * @param values canonical values value
     */
    public EffectiveDeployment {
        java.util.Objects.requireNonNull(bindingId, "bindingId");
        java.util.Objects.requireNonNull(revision, "revision");
        java.util.Objects.requireNonNull(values, "values");
        values = values == null ? null : java.util.List.copyOf(values);
    }
}
