// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** The package version Tool Hosts run for one package id, with the service profile and globals Control built for it.
 *
 * @param packageId canonical packageId value
 * @param version canonical version value
 * @param packageDigest canonical packageDigest value
 * @param revision canonical revision value
 * @param serviceProfileDigest canonical serviceProfileDigest value
 * @param serviceProfileRevision canonical serviceProfileRevision value
 * @param globalsDigest canonical globalsDigest value
 * @param toolIds canonical toolIds value
 * @param deployedAtUnixMs canonical deployedAtUnixMs value
 * @param hostIds canonical hostIds value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record PackageDeployment(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageId", required = true) String packageId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "version", required = true) String version,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageDigest", required = true) String packageDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "revision", required = true) Long revision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "serviceProfileDigest", required = true) String serviceProfileDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "serviceProfileRevision", required = true) Long serviceProfileRevision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "globalsDigest", required = true) String globalsDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toolIds", required = true) java.util.List<String> toolIds,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deployedAtUnixMs", required = true) Long deployedAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "hostIds", required = false) java.util.List<String> hostIds
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param packageId canonical packageId value
     * @param version canonical version value
     * @param packageDigest canonical packageDigest value
     * @param revision canonical revision value
     * @param serviceProfileDigest canonical serviceProfileDigest value
     * @param serviceProfileRevision canonical serviceProfileRevision value
     * @param globalsDigest canonical globalsDigest value
     * @param toolIds canonical toolIds value
     * @param deployedAtUnixMs canonical deployedAtUnixMs value
     * @param hostIds canonical hostIds value
     */
    public PackageDeployment {
        java.util.Objects.requireNonNull(packageId, "packageId");
        java.util.Objects.requireNonNull(version, "version");
        java.util.Objects.requireNonNull(packageDigest, "packageDigest");
        java.util.Objects.requireNonNull(revision, "revision");
        java.util.Objects.requireNonNull(serviceProfileDigest, "serviceProfileDigest");
        java.util.Objects.requireNonNull(serviceProfileRevision, "serviceProfileRevision");
        java.util.Objects.requireNonNull(globalsDigest, "globalsDigest");
        java.util.Objects.requireNonNull(toolIds, "toolIds");
        toolIds = toolIds == null ? null : java.util.List.copyOf(toolIds);
        java.util.Objects.requireNonNull(deployedAtUnixMs, "deployedAtUnixMs");
        hostIds = hostIds == null ? null : java.util.List.copyOf(hostIds);
    }
}
