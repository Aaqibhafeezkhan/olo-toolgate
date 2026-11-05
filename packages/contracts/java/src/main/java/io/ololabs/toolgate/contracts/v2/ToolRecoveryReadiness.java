// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Fresh-build preflight for tools declaring downstream idempotency. Eligibility is configured capability only; each retry independently needs qualified complete history, current policy/consent and its original window. No key, receipt or credential is exposed.
 *
 * @param tenantId canonical tenantId value
 * @param toolId canonical toolId value
 * @param name canonical name value
 * @param automaticResendEligible canonical automaticResendEligible value
 * @param effectiveMethod canonical effectiveMethod value
 * @param reason canonical reason value
 * @param routes canonical routes value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ToolRecoveryReadiness(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tenantId", required = true) String tenantId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toolId", required = true) String toolId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "name", required = true) String name,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "automaticResendEligible", required = true) Boolean automaticResendEligible,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "effectiveMethod", required = true) io.ololabs.toolgate.contracts.ReconciliationMethod effectiveMethod,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "reason", required = true) RecoveryReadinessReason reason,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "routes", required = true) java.util.List<RouteRecoveryReadiness> routes
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param tenantId canonical tenantId value
     * @param toolId canonical toolId value
     * @param name canonical name value
     * @param automaticResendEligible canonical automaticResendEligible value
     * @param effectiveMethod canonical effectiveMethod value
     * @param reason canonical reason value
     * @param routes canonical routes value
     */
    public ToolRecoveryReadiness {
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        java.util.Objects.requireNonNull(toolId, "toolId");
        java.util.Objects.requireNonNull(name, "name");
        java.util.Objects.requireNonNull(automaticResendEligible, "automaticResendEligible");
        java.util.Objects.requireNonNull(effectiveMethod, "effectiveMethod");
        java.util.Objects.requireNonNull(reason, "reason");
        java.util.Objects.requireNonNull(routes, "routes");
        routes = routes == null ? null : java.util.List.copyOf(routes);
    }
}
