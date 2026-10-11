// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Executable artifact inside the package; artifactDigest = sha256 over its exact bytes.
 *
 * @param artifactId canonical artifactId value
 * @param path canonical path value
 * @param artifactDigest canonical artifactDigest value
 * @param sizeBytes canonical sizeBytes value
 * @param mediaType canonical mediaType value
 * @param runtimeId canonical runtimeId value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record PackagedArtifact(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "artifactId", required = true) String artifactId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "path", required = true) String path,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "artifactDigest", required = true) String artifactDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "sizeBytes", required = true) Long sizeBytes,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "mediaType", required = true) String mediaType,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "runtimeId", required = false) String runtimeId
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param artifactId canonical artifactId value
     * @param path canonical path value
     * @param artifactDigest canonical artifactDigest value
     * @param sizeBytes canonical sizeBytes value
     * @param mediaType canonical mediaType value
     * @param runtimeId canonical runtimeId value
     */
    public PackagedArtifact {
        java.util.Objects.requireNonNull(artifactId, "artifactId");
        java.util.Objects.requireNonNull(path, "path");
        java.util.Objects.requireNonNull(artifactDigest, "artifactDigest");
        java.util.Objects.requireNonNull(sizeBytes, "sizeBytes");
        java.util.Objects.requireNonNull(mediaType, "mediaType");
    }
}
