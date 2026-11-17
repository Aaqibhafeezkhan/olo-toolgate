// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** WorkflowBundleManifest portable metadata.
 *
 * @param format canonical format value
 * @param version canonical version value
 * @param workflowId canonical workflowId value
 * @param sha256 canonical sha256 value
 * @param referencesSha256 canonical referencesSha256 value
 * @param build canonical build value
 * @param connections canonical connections value
 * @param packages canonical packages value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowBundleManifest(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "format", required = true) String format,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "version", required = true) Long version,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "workflowId", required = true) String workflowId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "sha256", required = true) String sha256,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "referencesSha256", required = true) String referencesSha256,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "build", required = false) WorkflowBundleBuild build,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "connections", required = true) java.util.List<WorkflowBundleConnection> connections,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packages", required = true) java.util.List<WorkflowBundlePackage> packages
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param format canonical format value
     * @param version canonical version value
     * @param workflowId canonical workflowId value
     * @param sha256 canonical sha256 value
     * @param referencesSha256 canonical referencesSha256 value
     * @param build canonical build value
     * @param connections canonical connections value
     * @param packages canonical packages value
     */
    public WorkflowBundleManifest {
        java.util.Objects.requireNonNull(format, "format");
        if (format != null && !java.util.Objects.equals(format, "olo-workflow")) throw new IllegalArgumentException("format must be olo-workflow");
        java.util.Objects.requireNonNull(version, "version");
        if (version != null && !java.util.Objects.equals(version, 2L)) throw new IllegalArgumentException("version must be 2");
        java.util.Objects.requireNonNull(workflowId, "workflowId");
        java.util.Objects.requireNonNull(sha256, "sha256");
        java.util.Objects.requireNonNull(referencesSha256, "referencesSha256");
        java.util.Objects.requireNonNull(connections, "connections");
        connections = connections == null ? null : java.util.List.copyOf(connections);
        java.util.Objects.requireNonNull(packages, "packages");
        packages = packages == null ? null : java.util.List.copyOf(packages);
    }
}
