// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** One package the profile requires, pinned to an exact version. A MARKETPLACE entry must also pin its package digest; a PREBUILT_CORE entry resolves its digest from the sealed image lock, and a declared digest must equal it. hostIds names the Tool Hosts that receive its eligible ServerTool exports; ClientTool exports never deploy to a Tool Host. deviceGroupIds names the device groups whose active, approved devices receive its device-runnable exports as a signed client release (ADR 026), including devices that join later. An entry that activates exports needs hostIds or deviceGroupIds. bindingIds have their allowed package digests set to exactly the profile packages that name them.
 *
 * @param packageId canonical packageId value
 * @param source canonical source value
 * @param version canonical version value
 * @param packageDigest canonical packageDigest value
 * @param required canonical required value
 * @param hostIds canonical hostIds value
 * @param deviceGroupIds canonical deviceGroupIds value
 * @param globals canonical globals value
 * @param tools canonical tools value
 * @param bindingIds canonical bindingIds value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ProfilePackage(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageId", required = true) String packageId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "source", required = true) ProfilePackageSource source,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "version", required = true) String version,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageDigest", required = false) String packageDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "required", required = true) Boolean required,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "hostIds", required = false) java.util.List<String> hostIds,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deviceGroupIds", required = false) java.util.List<String> deviceGroupIds,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "globals", required = false) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> globals,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tools", required = false) java.util.List<ProfileToolActivation> tools,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "bindingIds", required = false) java.util.List<String> bindingIds
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param packageId canonical packageId value
     * @param source canonical source value
     * @param version canonical version value
     * @param packageDigest canonical packageDigest value
     * @param required canonical required value
     * @param hostIds canonical hostIds value
     * @param deviceGroupIds canonical deviceGroupIds value
     * @param globals canonical globals value
     * @param tools canonical tools value
     * @param bindingIds canonical bindingIds value
     */
    public ProfilePackage {
        java.util.Objects.requireNonNull(packageId, "packageId");
        java.util.Objects.requireNonNull(source, "source");
        java.util.Objects.requireNonNull(version, "version");
        java.util.Objects.requireNonNull(required, "required");
        hostIds = hostIds == null ? null : java.util.List.copyOf(hostIds);
        deviceGroupIds = deviceGroupIds == null ? null : java.util.List.copyOf(deviceGroupIds);
        globals = globals == null ? null : java.util.Map.copyOf(globals);
        tools = tools == null ? null : java.util.List.copyOf(tools);
        bindingIds = bindingIds == null ? null : java.util.List.copyOf(bindingIds);
    }
}
