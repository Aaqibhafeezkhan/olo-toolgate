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
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowStackTriggerSource(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "subscription", required = true) String subscription,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "assignment", required = true) String assignment,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageId", required = true) String packageId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "version", required = true) String version,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "triggerId", required = true) String triggerId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deviceGroupId", required = true) String deviceGroupId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "config", required = true) WorkflowStackTriggerConfig config
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
     */
    public WorkflowStackTriggerSource {
        java.util.Objects.requireNonNull(subscription, "subscription");
        java.util.Objects.requireNonNull(assignment, "assignment");
        java.util.Objects.requireNonNull(packageId, "packageId");
        if (packageId != null && !java.util.Objects.equals(packageId, "examples.inbox-watch")) throw new IllegalArgumentException("packageId must be examples.inbox-watch");
        java.util.Objects.requireNonNull(version, "version");
        if (version != null && !java.util.Objects.equals(version, "0.1.0")) throw new IllegalArgumentException("version must be 0.1.0");
        java.util.Objects.requireNonNull(triggerId, "triggerId");
        if (triggerId != null && !java.util.Objects.equals(triggerId, "examples.inbox-watch/new-file")) throw new IllegalArgumentException("triggerId must be examples.inbox-watch/new-file");
        java.util.Objects.requireNonNull(deviceGroupId, "deviceGroupId");
        java.util.Objects.requireNonNull(config, "config");
    }
}
