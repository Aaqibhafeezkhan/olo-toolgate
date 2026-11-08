// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Body of POST /api/control/v1/triggers/subscriptions.
 *
 * @param name canonical name value
 * @param spec canonical spec value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerSubscriptionCreate(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "name", required = true) String name,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "spec", required = true) TriggerSubscriptionSpec spec
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param name canonical name value
     * @param spec canonical spec value
     */
    public TriggerSubscriptionCreate {
        java.util.Objects.requireNonNull(name, "name");
        java.util.Objects.requireNonNull(spec, "spec");
    }
}
