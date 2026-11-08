// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** One record Control stored for an instance (plan 6.4): the event as the executor sent it, the producer Control attached from the certificate, the lease and the signed package, and when Control received it.
 *
 * @param event canonical event value
 * @param producer canonical producer value
 * @param receivedAtUnixMs canonical receivedAtUnixMs value
 * @param disposition canonical disposition value
 * @param classification canonical classification value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerStoredEvent(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "event", required = true) TriggerCloudEvent event,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "producer", required = true) TriggerProducer producer,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "receivedAtUnixMs", required = true) Long receivedAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "disposition", required = false) TriggerDispositionView disposition,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "classification", required = false) TriggerClassification classification
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param event canonical event value
     * @param producer canonical producer value
     * @param receivedAtUnixMs canonical receivedAtUnixMs value
     * @param disposition canonical disposition value
     * @param classification canonical classification value
     */
    public TriggerStoredEvent {
        java.util.Objects.requireNonNull(event, "event");
        java.util.Objects.requireNonNull(producer, "producer");
        java.util.Objects.requireNonNull(receivedAtUnixMs, "receivedAtUnixMs");
    }
}
