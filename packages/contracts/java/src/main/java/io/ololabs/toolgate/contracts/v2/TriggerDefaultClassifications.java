// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Tenant fail-closed defaults for unlabelled data, per integration kind (plan 12.3). A default may be raised, never lowered below the shipped value.
 *
 * @param sdkPackage canonical sdkPackage value
 * @param mcpProxy canonical mcpProxy value
 * @param external canonical external value
 * @param unknown canonical unknown value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerDefaultClassifications(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "sdkPackage", required = true) TriggerClassification sdkPackage,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "mcpProxy", required = true) TriggerClassification mcpProxy,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "external", required = true) TriggerClassification external,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "unknown", required = true) TriggerClassification unknown
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param sdkPackage canonical sdkPackage value
     * @param mcpProxy canonical mcpProxy value
     * @param external canonical external value
     * @param unknown canonical unknown value
     */
    public TriggerDefaultClassifications {
        java.util.Objects.requireNonNull(sdkPackage, "sdkPackage");
        java.util.Objects.requireNonNull(mcpProxy, "mcpProxy");
        java.util.Objects.requireNonNull(external, "external");
        java.util.Objects.requireNonNull(unknown, "unknown");
    }
}
