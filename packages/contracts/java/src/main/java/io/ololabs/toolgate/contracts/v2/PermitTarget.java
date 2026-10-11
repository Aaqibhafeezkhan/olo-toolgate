// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Exact code and configuration the attempt may run.
 *
 * @param toolId canonical toolId value
 * @param toolDescriptorDigest canonical toolDescriptorDigest value
 * @param packageDigest canonical packageDigest value
 * @param artifactDigest canonical artifactDigest value
 * @param runtimeDigest canonical runtimeDigest value
 * @param globalsDigest canonical globalsDigest value
 * @param serviceProfileDigest canonical serviceProfileDigest value
 * @param serviceProfileRevision canonical serviceProfileRevision value
 * @param deploymentBindingDigest canonical deploymentBindingDigest value
 * @param deploymentBindingRevision canonical deploymentBindingRevision value
 * @param configRevision canonical configRevision value
 * @param routes canonical routes value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record PermitTarget(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toolId", required = true) String toolId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toolDescriptorDigest", required = true) String toolDescriptorDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageDigest", required = true) String packageDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "artifactDigest", required = true) String artifactDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "runtimeDigest", required = true) String runtimeDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "globalsDigest", required = true) String globalsDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "serviceProfileDigest", required = true) String serviceProfileDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "serviceProfileRevision", required = true) Long serviceProfileRevision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deploymentBindingDigest", required = true) String deploymentBindingDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deploymentBindingRevision", required = true) Long deploymentBindingRevision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "configRevision", required = true) Long configRevision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "routes", required = true) java.util.List<String> routes
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param toolId canonical toolId value
     * @param toolDescriptorDigest canonical toolDescriptorDigest value
     * @param packageDigest canonical packageDigest value
     * @param artifactDigest canonical artifactDigest value
     * @param runtimeDigest canonical runtimeDigest value
     * @param globalsDigest canonical globalsDigest value
     * @param serviceProfileDigest canonical serviceProfileDigest value
     * @param serviceProfileRevision canonical serviceProfileRevision value
     * @param deploymentBindingDigest canonical deploymentBindingDigest value
     * @param deploymentBindingRevision canonical deploymentBindingRevision value
     * @param configRevision canonical configRevision value
     * @param routes canonical routes value
     */
    public PermitTarget {
        java.util.Objects.requireNonNull(toolId, "toolId");
        java.util.Objects.requireNonNull(toolDescriptorDigest, "toolDescriptorDigest");
        java.util.Objects.requireNonNull(packageDigest, "packageDigest");
        java.util.Objects.requireNonNull(artifactDigest, "artifactDigest");
        java.util.Objects.requireNonNull(runtimeDigest, "runtimeDigest");
        java.util.Objects.requireNonNull(globalsDigest, "globalsDigest");
        java.util.Objects.requireNonNull(serviceProfileDigest, "serviceProfileDigest");
        java.util.Objects.requireNonNull(serviceProfileRevision, "serviceProfileRevision");
        java.util.Objects.requireNonNull(deploymentBindingDigest, "deploymentBindingDigest");
        java.util.Objects.requireNonNull(deploymentBindingRevision, "deploymentBindingRevision");
        java.util.Objects.requireNonNull(configRevision, "configRevision");
        java.util.Objects.requireNonNull(routes, "routes");
        routes = routes == null ? null : java.util.List.copyOf(routes);
    }
}
