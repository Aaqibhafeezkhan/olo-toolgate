// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Complete workflow-scoped local deployment declaration. Operator images and custody remain explicitly trusted; this grants no model or tool authority.
 *
 * @param enabled canonical enabled value
 * @param runtime canonical runtime value
 * @param platform canonical platform value
 * @param model canonical model value
 * @param source canonical source value
 * @param fileAgent canonical fileAgent value
 * @param monitoring canonical monitoring value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowStackDeployment(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "enabled", required = true) Boolean enabled,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "runtime", required = true) WorkflowStackRuntime runtime,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "platform", required = true) WorkflowStackPlatform platform,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "model", required = true) WorkflowStackModelEngine model,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "source", required = true) WorkflowStackTriggerSource source,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "fileAgent", required = false) WorkflowStackFolderAgent fileAgent,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "monitoring", required = true) WorkflowStackMonitoring monitoring
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param enabled canonical enabled value
     * @param runtime canonical runtime value
     * @param platform canonical platform value
     * @param model canonical model value
     * @param source canonical source value
     * @param fileAgent canonical fileAgent value
     * @param monitoring canonical monitoring value
     */
    public WorkflowStackDeployment {
        java.util.Objects.requireNonNull(enabled, "enabled");
        java.util.Objects.requireNonNull(runtime, "runtime");
        java.util.Objects.requireNonNull(platform, "platform");
        java.util.Objects.requireNonNull(model, "model");
        java.util.Objects.requireNonNull(source, "source");
        java.util.Objects.requireNonNull(monitoring, "monitoring");
    }
}
