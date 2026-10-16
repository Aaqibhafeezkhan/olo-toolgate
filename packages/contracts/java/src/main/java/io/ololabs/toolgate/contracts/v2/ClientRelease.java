// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** A client release: the image index pinned by digest, the exact FleetPackageDocument bytes (manifestDigest) and, when Control holds the package-release key, the signed FleetPackageRelease it recorded.
 *
 * @param packageId canonical packageId value
 * @param version canonical version value
 * @param packageDigest canonical packageDigest value
 * @param image canonical image value
 * @param runtimeVersion canonical runtimeVersion value
 * @param manifestDigest canonical manifestDigest value
 * @param signed canonical signed value
 * @param document canonical document value
 * @param documentBase64 canonical documentBase64 value
 * @param release canonical release value
 * @param tools canonical tools value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ClientRelease(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageId", required = true) String packageId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "version", required = true) String version,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageDigest", required = true) String packageDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "image", required = true) String image,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "runtimeVersion", required = true) String runtimeVersion,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "manifestDigest", required = true) String manifestDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "signed", required = true) Boolean signed,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "document", required = true) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> document,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "documentBase64", required = true) String documentBase64,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "release", required = false) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> release,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tools", required = true) java.util.List<ClientReleaseTool> tools
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param packageId canonical packageId value
     * @param version canonical version value
     * @param packageDigest canonical packageDigest value
     * @param image canonical image value
     * @param runtimeVersion canonical runtimeVersion value
     * @param manifestDigest canonical manifestDigest value
     * @param signed canonical signed value
     * @param document canonical document value
     * @param documentBase64 canonical documentBase64 value
     * @param release canonical release value
     * @param tools canonical tools value
     */
    public ClientRelease {
        java.util.Objects.requireNonNull(packageId, "packageId");
        java.util.Objects.requireNonNull(version, "version");
        java.util.Objects.requireNonNull(packageDigest, "packageDigest");
        java.util.Objects.requireNonNull(image, "image");
        java.util.Objects.requireNonNull(runtimeVersion, "runtimeVersion");
        java.util.Objects.requireNonNull(manifestDigest, "manifestDigest");
        java.util.Objects.requireNonNull(signed, "signed");
        java.util.Objects.requireNonNull(document, "document");
        document = document == null ? null : java.util.Map.copyOf(document);
        java.util.Objects.requireNonNull(documentBase64, "documentBase64");
        release = release == null ? null : java.util.Map.copyOf(release);
        java.util.Objects.requireNonNull(tools, "tools");
        tools = tools == null ? null : java.util.List.copyOf(tools);
    }
}
