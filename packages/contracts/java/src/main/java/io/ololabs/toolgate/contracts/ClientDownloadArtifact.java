// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Local tool boundary for signed managed registrations; validate schema before use.
 *
 * @param platform canonical platform value
 * @param target canonical target value
 * @param filename canonical filename value
 * @param sha256 canonical sha256 value
 * @param bytes canonical bytes value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ClientDownloadArtifact(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "platform", required = true) ClientPlatform platform,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "target", required = true) String target,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "filename", required = true) String filename,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "sha256", required = true) String sha256,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "bytes", required = true) Long bytes
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param platform canonical platform value
     * @param target canonical target value
     * @param filename canonical filename value
     * @param sha256 canonical sha256 value
     * @param bytes canonical bytes value
     */
    public ClientDownloadArtifact {
        java.util.Objects.requireNonNull(platform, "platform");
        java.util.Objects.requireNonNull(target, "target");
        java.util.Objects.requireNonNull(filename, "filename");
        java.util.Objects.requireNonNull(sha256, "sha256");
        java.util.Objects.requireNonNull(bytes, "bytes");
    }
}
