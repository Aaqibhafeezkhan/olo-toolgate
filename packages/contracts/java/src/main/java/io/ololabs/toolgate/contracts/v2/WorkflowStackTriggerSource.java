// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Workflow-scoped operator deployment data; structural configuration confers no runtime authority.
 *
 * @param subscription canonical subscription value
 * @param assignment canonical assignment value
 * @param packageId canonical packageId value
 * @param version canonical version value
 * @param triggerId canonical triggerId value
 * @param deviceGroupId canonical deviceGroupId value
 * @param config canonical config value
 * @param eventFilter canonical eventFilter value
 * @param packageDigest canonical packageDigest value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowStackTriggerSource(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "subscription", required = true) String subscription,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "assignment", required = true) String assignment,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageId", required = true) String packageId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "version", required = true) String version,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "triggerId", required = true) String triggerId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deviceGroupId", required = true) String deviceGroupId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "config", required = true) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> config,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "eventFilter", required = true) String eventFilter,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageDigest", required = false) String packageDigest
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param subscription canonical subscription value
     * @param assignment canonical assignment value
     * @param packageId canonical packageId value
     * @param version canonical version value
     * @param triggerId canonical triggerId value
     * @param deviceGroupId canonical deviceGroupId value
     * @param config canonical config value
     * @param eventFilter canonical eventFilter value
     * @param packageDigest canonical packageDigest value
     */
    public WorkflowStackTriggerSource {
        java.util.Objects.requireNonNull(subscription, "subscription");
        java.util.Objects.requireNonNull(assignment, "assignment");
        java.util.Objects.requireNonNull(packageId, "packageId");
        java.util.Objects.requireNonNull(version, "version");
        java.util.Objects.requireNonNull(triggerId, "triggerId");
        java.util.Objects.requireNonNull(deviceGroupId, "deviceGroupId");
        java.util.Objects.requireNonNull(config, "config");
        config = config == null ? null : java.util.Map.copyOf(config);
        java.util.Objects.requireNonNull(eventFilter, "eventFilter");
    }
}
