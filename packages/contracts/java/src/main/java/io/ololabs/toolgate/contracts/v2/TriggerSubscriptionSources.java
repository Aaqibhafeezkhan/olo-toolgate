// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Explicit sources of a subscription (plan 13.1); there is no wildcard. Control refuses a source listed twice.
 *
 * @param assignments canonical assignments value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerSubscriptionSources(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "assignments", required = true) java.util.List<String> assignments
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param assignments canonical assignments value
     */
    public TriggerSubscriptionSources {
        java.util.Objects.requireNonNull(assignments, "assignments");
        assignments = assignments == null ? null : java.util.List.copyOf(assignments);
    }
}
