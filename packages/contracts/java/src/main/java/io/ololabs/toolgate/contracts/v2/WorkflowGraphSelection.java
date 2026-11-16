// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Pinned operator-installed native LangGraph factory. Configuration grants no runtime authority.
 *
 * @param packageId canonical packageId value
 * @param version canonical version value
 * @param digest canonical digest value
 * @param entryPoint canonical entryPoint value
 * @param config canonical config value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowGraphSelection(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageId", required = true) String packageId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "version", required = true) String version,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "digest", required = true) String digest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "entryPoint", required = true) String entryPoint,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "config", required = true) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> config
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param packageId canonical packageId value
     * @param version canonical version value
     * @param digest canonical digest value
     * @param entryPoint canonical entryPoint value
     * @param config canonical config value
     */
    public WorkflowGraphSelection {
        java.util.Objects.requireNonNull(packageId, "packageId");
        java.util.Objects.requireNonNull(version, "version");
        java.util.Objects.requireNonNull(digest, "digest");
        java.util.Objects.requireNonNull(entryPoint, "entryPoint");
        java.util.Objects.requireNonNull(config, "config");
        config = config == null ? null : java.util.Map.copyOf(config);
    }
}
