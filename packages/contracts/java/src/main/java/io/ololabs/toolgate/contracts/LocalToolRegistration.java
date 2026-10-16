// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Protected local organization registration, separate from marketplace trust and online Gateway authorization.
 *
 * @param toolId canonical toolId value
 * @param action canonical action value
 * @param runtimeId canonical runtimeId value
 * @param entryPoint canonical entryPoint value
 * @param inputSchema canonical inputSchema value
 * @param outputSchema canonical outputSchema value
 * @param limits canonical limits value
 * @param authorizationProfile canonical authorizationProfile value
 * @param fileAccess canonical fileAccess value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record LocalToolRegistration(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toolId", required = true) String toolId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "action", required = true) String action,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "runtimeId", required = true) String runtimeId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "entryPoint", required = true) String entryPoint,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "inputSchema", required = true) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> inputSchema,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "outputSchema", required = true) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> outputSchema,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "limits", required = true) LocalRuntimeLimits limits,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "authorizationProfile", required = true) InstalledAuthorizationProfile authorizationProfile,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "fileAccess", required = false) LocalFileAccess fileAccess
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param toolId canonical toolId value
     * @param action canonical action value
     * @param runtimeId canonical runtimeId value
     * @param entryPoint canonical entryPoint value
     * @param inputSchema canonical inputSchema value
     * @param outputSchema canonical outputSchema value
     * @param limits canonical limits value
     * @param authorizationProfile canonical authorizationProfile value
     * @param fileAccess canonical fileAccess value
     */
    public LocalToolRegistration {
        java.util.Objects.requireNonNull(toolId, "toolId");
        java.util.Objects.requireNonNull(action, "action");
        java.util.Objects.requireNonNull(runtimeId, "runtimeId");
        java.util.Objects.requireNonNull(entryPoint, "entryPoint");
        java.util.Objects.requireNonNull(inputSchema, "inputSchema");
        inputSchema = inputSchema == null ? null : java.util.Map.copyOf(inputSchema);
        java.util.Objects.requireNonNull(outputSchema, "outputSchema");
        outputSchema = outputSchema == null ? null : java.util.Map.copyOf(outputSchema);
        java.util.Objects.requireNonNull(limits, "limits");
        java.util.Objects.requireNonNull(authorizationProfile, "authorizationProfile");
    }
}
