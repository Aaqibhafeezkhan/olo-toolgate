// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Workflow-scoped operator deployment data; structural configuration confers no runtime authority.
 *
 * @param enabled canonical enabled value
 * @param mode canonical mode value
 * @param grafanaUser canonical grafanaUser value
 * @param grafanaSecret canonical grafanaSecret value
 * @param grafanaPort canonical grafanaPort value
 * @param prometheusPort canonical prometheusPort value
 * @param collectorConfig canonical collectorConfig value
 * @param spanSampleRatio canonical spanSampleRatio value
 * @param images canonical images value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowStackMonitoring(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "enabled", required = true) Boolean enabled,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "mode", required = true) String mode,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "grafanaUser", required = true) String grafanaUser,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "grafanaSecret", required = true) String grafanaSecret,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "grafanaPort", required = true) Long grafanaPort,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "prometheusPort", required = true) Long prometheusPort,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "collectorConfig", required = true) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> collectorConfig,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "spanSampleRatio", required = true) Double spanSampleRatio,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "images", required = true) WorkflowStackMonitoringImages images
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param enabled canonical enabled value
     * @param mode canonical mode value
     * @param grafanaUser canonical grafanaUser value
     * @param grafanaSecret canonical grafanaSecret value
     * @param grafanaPort canonical grafanaPort value
     * @param prometheusPort canonical prometheusPort value
     * @param collectorConfig canonical collectorConfig value
     * @param spanSampleRatio canonical spanSampleRatio value
     * @param images canonical images value
     */
    public WorkflowStackMonitoring {
        java.util.Objects.requireNonNull(enabled, "enabled");
        java.util.Objects.requireNonNull(mode, "mode");
        java.util.Objects.requireNonNull(grafanaUser, "grafanaUser");
        java.util.Objects.requireNonNull(grafanaSecret, "grafanaSecret");
        java.util.Objects.requireNonNull(grafanaPort, "grafanaPort");
        java.util.Objects.requireNonNull(prometheusPort, "prometheusPort");
        java.util.Objects.requireNonNull(collectorConfig, "collectorConfig");
        collectorConfig = collectorConfig == null ? null : java.util.Map.copyOf(collectorConfig);
        java.util.Objects.requireNonNull(spanSampleRatio, "spanSampleRatio");
        java.util.Objects.requireNonNull(images, "images");
    }
}
