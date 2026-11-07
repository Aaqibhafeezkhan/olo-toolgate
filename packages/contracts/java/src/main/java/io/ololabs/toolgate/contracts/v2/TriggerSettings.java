// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Tenant trigger settings (GET and PUT /api/control/v1/triggers/settings).
 *
 * @param eventRetentionDays canonical eventRetentionDays value
 * @param defaultClassification canonical defaultClassification value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerSettings(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "eventRetentionDays", required = true) Long eventRetentionDays,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "defaultClassification", required = true) TriggerDefaultClassifications defaultClassification
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param eventRetentionDays canonical eventRetentionDays value
     * @param defaultClassification canonical defaultClassification value
     */
    public TriggerSettings {
        java.util.Objects.requireNonNull(eventRetentionDays, "eventRetentionDays");
        java.util.Objects.requireNonNull(defaultClassification, "defaultClassification");
    }
}
