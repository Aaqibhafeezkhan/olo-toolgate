// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** WorkflowBundlePackage portable metadata.
 *
 * @param packageId canonical packageId value
 * @param version canonical version value
 * @param packageDigest canonical packageDigest value
 * @param sha256 canonical sha256 value
 * @param bytes canonical bytes value
 * @param path canonical path value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowBundlePackage(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageId", required = true) String packageId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "version", required = true) String version,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageDigest", required = true) String packageDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "sha256", required = true) String sha256,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "bytes", required = true) Long bytes,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "path", required = true) String path
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param packageId canonical packageId value
     * @param version canonical version value
     * @param packageDigest canonical packageDigest value
     * @param sha256 canonical sha256 value
     * @param bytes canonical bytes value
     * @param path canonical path value
     */
    public WorkflowBundlePackage {
        java.util.Objects.requireNonNull(packageId, "packageId");
        java.util.Objects.requireNonNull(version, "version");
        java.util.Objects.requireNonNull(packageDigest, "packageDigest");
        java.util.Objects.requireNonNull(sha256, "sha256");
        java.util.Objects.requireNonNull(bytes, "bytes");
        java.util.Objects.requireNonNull(path, "path");
    }
}
