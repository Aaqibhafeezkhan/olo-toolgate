// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Package manifest v2. v1 manifests stay readable and are upgraded on read.
 *
 * @param schemaVersion canonical schemaVersion value
 * @param kind canonical kind value
 * @param id canonical id value
 * @param version canonical version value
 * @param publisher canonical publisher value
 * @param sdk canonical sdk value
 * @param compatibility canonical compatibility value
 * @param runtimes canonical runtimes value
 * @param tools canonical tools value
 * @param descriptorSetDigest canonical descriptorSetDigest value
 * @param artifacts canonical artifacts value
 * @param credentialReferences canonical credentialReferences value
 * @param connections canonical connections value
 * @param composition canonical composition value
 * @param limits canonical limits value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record PackageManifest(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "schemaVersion", required = true) Long schemaVersion,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "kind", required = true) PackageKind kind,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "id", required = true) String id,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "version", required = true) String version,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "publisher", required = true) String publisher,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "sdk", required = false) SdkInfo sdk,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "compatibility", required = true) PackageCompatibility compatibility,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "runtimes", required = true) java.util.List<RuntimeDeclaration> runtimes,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tools", required = true) java.util.List<ToolDefinition> tools,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "descriptorSetDigest", required = true) String descriptorSetDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "artifacts", required = true) java.util.List<PackagedArtifact> artifacts,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "credentialReferences", required = true) java.util.List<String> credentialReferences,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "connections", required = true) java.util.List<ConnectionRequirement> connections,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "composition", required = true) java.util.List<CompositionRecord> composition,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "limits", required = true) PackageLimits limits
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param schemaVersion canonical schemaVersion value
     * @param kind canonical kind value
     * @param id canonical id value
     * @param version canonical version value
     * @param publisher canonical publisher value
     * @param sdk canonical sdk value
     * @param compatibility canonical compatibility value
     * @param runtimes canonical runtimes value
     * @param tools canonical tools value
     * @param descriptorSetDigest canonical descriptorSetDigest value
     * @param artifacts canonical artifacts value
     * @param credentialReferences canonical credentialReferences value
     * @param connections canonical connections value
     * @param composition canonical composition value
     * @param limits canonical limits value
     */
    public PackageManifest {
        java.util.Objects.requireNonNull(schemaVersion, "schemaVersion");
        if (schemaVersion != null && !java.util.Objects.equals(schemaVersion, 2L)) throw new IllegalArgumentException("schemaVersion must be 2");
        java.util.Objects.requireNonNull(kind, "kind");
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(version, "version");
        java.util.Objects.requireNonNull(publisher, "publisher");
        java.util.Objects.requireNonNull(compatibility, "compatibility");
        java.util.Objects.requireNonNull(runtimes, "runtimes");
        runtimes = runtimes == null ? null : java.util.List.copyOf(runtimes);
        java.util.Objects.requireNonNull(tools, "tools");
        tools = tools == null ? null : java.util.List.copyOf(tools);
        java.util.Objects.requireNonNull(descriptorSetDigest, "descriptorSetDigest");
        java.util.Objects.requireNonNull(artifacts, "artifacts");
        artifacts = artifacts == null ? null : java.util.List.copyOf(artifacts);
        java.util.Objects.requireNonNull(credentialReferences, "credentialReferences");
        credentialReferences = credentialReferences == null ? null : java.util.List.copyOf(credentialReferences);
        java.util.Objects.requireNonNull(connections, "connections");
        connections = connections == null ? null : java.util.List.copyOf(connections);
        java.util.Objects.requireNonNull(composition, "composition");
        composition = composition == null ? null : java.util.List.copyOf(composition);
        java.util.Objects.requireNonNull(limits, "limits");
    }
}
