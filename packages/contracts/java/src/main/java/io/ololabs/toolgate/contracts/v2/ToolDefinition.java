// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Tool definition v2: the semantic descriptor plus packaging members outside the digest.
 *
 * @param descriptor canonical descriptor value
 * @param toolDescriptorDigest canonical toolDescriptorDigest value
 * @param runtimeId canonical runtimeId value
 * @param entryPoint canonical entryPoint value
 * @param artifactIds canonical artifactIds value
 * @param limits canonical limits value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ToolDefinition(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "descriptor", required = true) ToolDescriptor descriptor,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toolDescriptorDigest", required = true) String toolDescriptorDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "runtimeId", required = false) String runtimeId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "entryPoint", required = false) String entryPoint,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "artifactIds", required = true) java.util.List<String> artifactIds,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "limits", required = false) ToolLimits limits
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param descriptor canonical descriptor value
     * @param toolDescriptorDigest canonical toolDescriptorDigest value
     * @param runtimeId canonical runtimeId value
     * @param entryPoint canonical entryPoint value
     * @param artifactIds canonical artifactIds value
     * @param limits canonical limits value
     */
    public ToolDefinition {
        java.util.Objects.requireNonNull(descriptor, "descriptor");
        java.util.Objects.requireNonNull(toolDescriptorDigest, "toolDescriptorDigest");
        java.util.Objects.requireNonNull(artifactIds, "artifactIds");
        artifactIds = artifactIds == null ? null : java.util.List.copyOf(artifactIds);
    }
}
