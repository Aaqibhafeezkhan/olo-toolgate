// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** The consumer of a delivery.
 *
 * @param kind canonical kind value
 * @param name canonical name value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerDeliveryConsumer(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "kind", required = true) TriggerConsumerKind kind,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "name", required = true) String name
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param kind canonical kind value
     * @param name canonical name value
     */
    public TriggerDeliveryConsumer {
        java.util.Objects.requireNonNull(kind, "kind");
        java.util.Objects.requireNonNull(name, "name");
    }
}
