// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Local tool boundary for signed managed registrations; validate schema before use.
 *
 * @param version canonical version value
 * @param artifacts canonical artifacts value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ClientDownloadManifest(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "version", required = true) String version,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "artifacts", required = true) java.util.List<ClientDownloadArtifact> artifacts
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param version canonical version value
     * @param artifacts canonical artifacts value
     */
    public ClientDownloadManifest {
        java.util.Objects.requireNonNull(version, "version");
        java.util.Objects.requireNonNull(artifacts, "artifacts");
        artifacts = artifacts == null ? null : java.util.List.copyOf(artifacts);
    }
}
