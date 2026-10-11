// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Where the package runs.
 *
 * @param kind canonical kind value
 * @param ids canonical ids value
 * @param allowedTiers canonical allowedTiers value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record DeploymentTarget(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "kind", required = true) TargetKind kind,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "ids", required = true) java.util.List<String> ids,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "allowedTiers", required = true) java.util.List<RuntimeTier> allowedTiers
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param kind canonical kind value
     * @param ids canonical ids value
     * @param allowedTiers canonical allowedTiers value
     */
    public DeploymentTarget {
        java.util.Objects.requireNonNull(kind, "kind");
        java.util.Objects.requireNonNull(ids, "ids");
        ids = ids == null ? null : java.util.List.copyOf(ids);
        java.util.Objects.requireNonNull(allowedTiers, "allowedTiers");
        allowedTiers = allowedTiers == null ? null : java.util.List.copyOf(allowedTiers);
    }
}
