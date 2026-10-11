// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** A tenant's uploaded package version.
 *
 * @param packageId canonical packageId value
 * @param version canonical version value
 * @param packageDigest canonical packageDigest value
 * @param descriptorSetDigest canonical descriptorSetDigest value
 * @param state canonical state value
 * @param revision canonical revision value
 * @param uploadedAtUnixMs canonical uploadedAtUnixMs value
 * @param manifest canonical manifest value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record PackageVersion(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageId", required = true) String packageId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "version", required = true) String version,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageDigest", required = true) String packageDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "descriptorSetDigest", required = true) String descriptorSetDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "state", required = true) PackageVersionState state,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "revision", required = true) Long revision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "uploadedAtUnixMs", required = true) Long uploadedAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "manifest", required = false) PackageManifest manifest
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param packageId canonical packageId value
     * @param version canonical version value
     * @param packageDigest canonical packageDigest value
     * @param descriptorSetDigest canonical descriptorSetDigest value
     * @param state canonical state value
     * @param revision canonical revision value
     * @param uploadedAtUnixMs canonical uploadedAtUnixMs value
     * @param manifest canonical manifest value
     */
    public PackageVersion {
        java.util.Objects.requireNonNull(packageId, "packageId");
        java.util.Objects.requireNonNull(version, "version");
        java.util.Objects.requireNonNull(packageDigest, "packageDigest");
        java.util.Objects.requireNonNull(descriptorSetDigest, "descriptorSetDigest");
        java.util.Objects.requireNonNull(state, "state");
        java.util.Objects.requireNonNull(revision, "revision");
        java.util.Objects.requireNonNull(uploadedAtUnixMs, "uploadedAtUnixMs");
    }
}
