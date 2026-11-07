// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Subscription resource.
 *
 * @param apiVersion canonical apiVersion value
 * @param kind canonical kind value
 * @param metadata canonical metadata value
 * @param spec canonical spec value
 * @param status canonical status value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerSubscription(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "apiVersion", required = true) String apiVersion,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "kind", required = true) String kind,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "metadata", required = true) TriggerAssignmentMetadata metadata,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "spec", required = true) TriggerSubscriptionSpec spec,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "status", required = true) TriggerSubscriptionStatus status
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param apiVersion canonical apiVersion value
     * @param kind canonical kind value
     * @param metadata canonical metadata value
     * @param spec canonical spec value
     * @param status canonical status value
     */
    public TriggerSubscription {
        java.util.Objects.requireNonNull(apiVersion, "apiVersion");
        if (apiVersion != null && !java.util.Objects.equals(apiVersion, "toolgate.olo/v1")) throw new IllegalArgumentException("apiVersion must be toolgate.olo/v1");
        java.util.Objects.requireNonNull(kind, "kind");
        if (kind != null && !java.util.Objects.equals(kind, "TriggerSubscription")) throw new IllegalArgumentException("kind must be TriggerSubscription");
        java.util.Objects.requireNonNull(metadata, "metadata");
        java.util.Objects.requireNonNull(spec, "spec");
        java.util.Objects.requireNonNull(status, "status");
    }
}
