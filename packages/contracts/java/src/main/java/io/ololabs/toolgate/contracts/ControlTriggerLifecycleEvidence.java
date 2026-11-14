// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Scope, reason and resolved state changes for a trigger assignment lifecycle action.
 *
 * @param action canonical action value
 * @param scopeKind canonical scopeKind value
 * @param scopeIds canonical scopeIds value
 * @param reason canonical reason value
 * @param changes canonical changes value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ControlTriggerLifecycleEvidence(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "action", required = true) io.ololabs.toolgate.contracts.v2.TriggerLifecycleAction action,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "scopeKind", required = true) String scopeKind,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "scopeIds", required = true) java.util.List<String> scopeIds,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "reason", required = true) String reason,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "changes", required = true) java.util.List<io.ololabs.toolgate.contracts.v2.TriggerInstanceChange> changes
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param action canonical action value
     * @param scopeKind canonical scopeKind value
     * @param scopeIds canonical scopeIds value
     * @param reason canonical reason value
     * @param changes canonical changes value
     */
    public ControlTriggerLifecycleEvidence {
        java.util.Objects.requireNonNull(action, "action");
        java.util.Objects.requireNonNull(scopeKind, "scopeKind");
        java.util.Objects.requireNonNull(scopeIds, "scopeIds");
        scopeIds = scopeIds == null ? null : java.util.List.copyOf(scopeIds);
        java.util.Objects.requireNonNull(reason, "reason");
        java.util.Objects.requireNonNull(changes, "changes");
        changes = changes == null ? null : java.util.List.copyOf(changes);
    }
}
